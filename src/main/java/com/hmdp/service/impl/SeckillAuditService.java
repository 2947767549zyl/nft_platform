package com.hmdp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.SeckillAuditLog;
import com.hmdp.mapper.SeckillAuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀对账与审计服务
 * 独立消费者组：seckill-audit-group
 */
@Service
@Slf4j
public class SeckillAuditService {

    @Resource
    private SeckillAuditLogMapper auditLogMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 监听秒杀订单消息（对账组）
     * groupId 必须与主业务消费者不同！
     */
    @KafkaListener(topics = "seckill-order-topic", groupId = "seckill-audit-group")
    public void consumeAuditLog(String message, Acknowledgment acknowledgment) {
        try {
            // 1. 解析消息
            JSONObject json = JSONUtil.parseObj(message);
            Long orderId = json.getLong("orderId");
            Long userId = json.getLong("userId");
            Long voucherId = json.getLong("voucherId");

            log.info("📝 [对账] 收到消息: orderId={}, userId={}", orderId, userId);

            // 2. 🔥 第一道防线：Redis SETNX 快速去重
            String redisKey = "audit:lock:" + orderId;
            Boolean isNew = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, "1", 24, TimeUnit.HOURS);

            if (Boolean.FALSE.equals(isNew)) {
                log.debug("⚠️ [对账] Redis 拦截重复消息: orderId={}", orderId);
                acknowledgment.acknowledge(); // 确认消费，不再处理
                return;
            }

            // 3. 🔥 第二道防线：数据库唯一索引兜底
            SeckillAuditLog auditLog = new SeckillAuditLog();
            auditLog.setOrderId(orderId);
            auditLog.setUserId(userId);
            auditLog.setVoucherId(voucherId);
            auditLog.setStatus("RECEIVED");

            try {
                auditLogMapper.insert(auditLog);
                log.info("✅ [对账] 日志入库成功: orderId={}", orderId);
            } catch (DuplicateKeyException e) {
                // 如果 Redis 失效或重启，DB 唯一索引会捕获重复
                log.warn("⚠️ [对账] DB 拦截重复记录: orderId={}", orderId);
            }

            // 4. 手动提交偏移量
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("❌ [对账] 处理异常: {}", message, e);
            // 注意：如果是数据格式错误，建议 ack 并记录死信，防止阻塞整个组
            // 如果是网络/DB 临时故障，不 ack，让 Kafka 重试
        }
    }
}
