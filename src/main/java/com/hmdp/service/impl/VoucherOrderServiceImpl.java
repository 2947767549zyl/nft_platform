package com.hmdp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    private IVoucherOrderService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 获取当前用户（Web 线程，UserHolder 有效）
        Long userId = UserHolder.getUser().getId();

        // 2. 执行 Lua 脚本（预减库存 + 一人一单校验）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );

        // 3. 判断结果
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "优惠券已售罄" : "不能重复购买");
        }

        // 4. 创建订单对象
        long orderId = redisIdWorker.nextId("order");

        // 5. 构造订单消息
        SeckillOrderMessage message = new SeckillOrderMessage(
                orderId,
                userId,
                voucherId,
                System.currentTimeMillis()
        );

        try {
            // 6. 发送到 Kafka（异步非阻塞）
            kafkaTemplate.send(
                    "seckill-order-topic",
                    String.valueOf(userId),
                    JSONUtil.toJsonStr(message)
            ).addCallback(
                    success -> log.info("订单消息已发送到 Kafka: orderId={}, userId={}, voucherId={}",
                            orderId, userId, voucherId),
                    failure -> log.error("发送 Kafka 消息失败：orderId={}", orderId, failure)
            );

        } catch (Exception e) {
            log.error("发送 Kafka 消息异常：orderId={}", orderId, e);
            // 降级处理：直接同步创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            createVoucherOrder(voucherOrder);
        }

        // 7. 立即返回订单 ID
        return Result.ok(orderId);
    }

    /**
     * 监听 Kafka 消息，异步落库
     */
    @KafkaListener(topics = "seckill-order-topic", groupId = "seckill-order-group")
    public void consumeSeckillOrder(String message, Acknowledgment acknowledgment) {
        try {
            log.info("收到 Kafka 订单消息：{}", message);

            // 1. 解析消息
            JSONObject json = JSONUtil.parseObj(message);
            Long orderId = json.getLong("orderId");
            Long userId = json.getLong("userId");
            Long voucherId = json.getLong("voucherId");

            // 2. 创建订单对象
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);

            // 🔥 注意：这里不能使用 UserHolder.getUser().getId()
            // 因为这是 Kafka 消费者线程，ThreadLocal 中没有用户信息
            // 必须从消息中获取 userId

            // 3. 直接调用事务方法（不再需要分布式锁）
            proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);

            log.info("✅ 订单创建成功：orderId={}, userId={}, voucherId={}",
                    orderId, userId, voucherId);

            // 4. 手动提交偏移量
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("❌ 消费 Kafka 消息失败：message={}", message, e);
            // 不提交偏移量，Kafka 会重新投递
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 🔥 关键修正：从订单对象中获取 userId，而不是 UserHolder
        Long userId = voucherOrder.getUserId();

        if (userId == null) {
            throw new RuntimeException("订单中缺少用户 ID");
        }

        // 1. 查询是否重复购买（数据库层面一人一单校验）
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();

        if (count > 0) {
            log.warn("检测到重复购买，跳过：userId={}, voucherId={}", userId, voucherOrder.getVoucherId());
            return;
        }

        // 2. 扣减库存（乐观锁防止超卖）
        boolean flag = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();

        if (!flag){
            throw new RuntimeException("秒杀券扣减失败");
        }

        // 3. 保存订单到数据库
        flag = this.save(voucherOrder);
        if (!flag){
            throw new RuntimeException("创建秒杀券订单失败");
        }

        log.info("✅ 订单入库成功：orderId={}, userId={}, voucherId={}",
                voucherOrder.getId(), userId, voucherOrder.getVoucherId());
    }
}
