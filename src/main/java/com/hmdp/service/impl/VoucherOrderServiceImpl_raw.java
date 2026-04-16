package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl_raw extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    //执行lua脚本，并判断，抢购成功的生成订单并存入阻塞队列
    private IVoucherOrderService proxy;//定义代理对象，提前定义后面会用到
    //注入脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建阻塞队列  这个阻塞队列特点：当一个线程尝试从队列获取元素的时候，如果没有元素该线程阻塞，直到队列中有元素才会被唤醒获取
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);//初始化阻塞队列的大小

    //创建一个线程池
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //利用spring提供的注解，在类初始化完毕后立即执行线程任务

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //创建线程任务，内部类方式
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            //1.获取队列中的订单信息
            while(true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单，这是调之前那个创建订单的方法，需要稍作改动
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.info("异常信息:", e);
                }
            }
        }

    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取锁
        boolean isLock = lock.tryLock(1200);
        //判断是否获取锁成功
        if (!isLock){
            log.error("您已购买过该商品，不能重复购买");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);//使用代理对象，最后用于提交事务
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();//释放锁
        }
    }

    //@Override
    public Result seckillVoucher_raw(Long voucherId) throws InterruptedException {
        // 1、查询秒杀券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2、判断秒杀券是否合法
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 秒杀券的开始时间在当前时间之后
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 秒杀券的结束时间在当前时间之前
            return Result.fail("秒杀已结束");
        }
        if (voucher.getStock() < 1) {
            return Result.fail("秒杀券已抢空");
        }

        Long userId = UserHolder.getUser().getId();
        return Result.ok();
        //这个单体环境下，使用synchronized来实现一人一单
//        synchronized (userId.toString().intern()) {
//            //获取代理对象，来防止类内调用事务方法产生事务失效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }

        //现在使用redis分布式锁来实现集群分布下一人一单
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        boolean isLocked = lock.tryLock(1000);
//        if (!isLocked) {
//            return Result.fail("请勿重复下单");
//        }
//        try {
//            // 5. 获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }

        //现在使用redisson来实现集群分布下一人一单
//        RLock lock = redissonClient.getLock("order:" + userId);
//        boolean isLocked = lock.tryLock(1, 10, TimeUnit.SECONDS);
//        if(!isLocked) {
//            return Result.fail("请勿重复下单");
//        }
//        try {
//            // 5. 获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) { //使用lua脚本
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(), //这里是key数组，没有key，就传的一个空集合
                voucherId.toString(), userId.toString()
        );
        //2.判断结果是0
        int r = result.intValue();//Long型转为int型，便于下面比较
        if (r != 0) {
            //2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "优惠券已售罄" : "不能重复购买");

        }
        //为零，有购买资格，把下单相信保存到一个阻塞队列
        //7.创建订单   向订单表新增一条数据，除默认字段，其他字段的值需要set
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //7.2用户id
        voucherOrder.setUserId(userId);
        //7.3代金券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞对列中
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.返回订单id
        return Result.ok(orderId);




    }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 这里额外多一步查询用户是否在数据库中存在
        //校验一人一单
        Long userId = UserHolder.getUser().getId();
        //查询用户是否已经购买过该秒杀券
        /*
        SELECT COUNT(*)
        FROM voucher_order
        WHERE user_id = 123456          -- 假设 userId 是 123456
        AND voucher_id = 789;         -- 假设 voucherId 是 789
        */
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        if (count > 0) {
            //return Result.fail("用户已经购买过该秒杀券");
            return;
        }
        // 5、秒杀券合法，则秒杀券抢购成功，秒杀券库存数量减一
        boolean flag = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder)
                .gt("stock", 0).//乐观锁，确保更新的是最新数据
                        update();
        if (!flag){
            throw new RuntimeException("秒杀券扣减失败");
        }


        // 6、秒杀成功，创建对应的订单，并保存到数据库
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //这里生成的 orderId 是唯一的，用于保证秒杀券订单的唯一性
//        long orderId = redisIdWorker.nextId("seckill:order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//        voucherOrder.setVoucherId(voucherOrder);
        flag = this.save(voucherOrder);
        if (!flag){
            throw new RuntimeException("创建秒杀券订单失败");
        }
        // 返回订单id
        //return Result.ok(orderId);
    }
}
