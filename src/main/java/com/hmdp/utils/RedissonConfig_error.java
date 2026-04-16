package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import lombok.var;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CompletableFuture;
@Slf4j
@Configuration
public class RedissonConfig_error {

    @Value("${spring.redis.host}")
    private String host;

    @Value("${spring.redis.port}")
    private int port;

    @Value("${spring.redis.password}")
    private String password;

    @Value("${spring.redis.database}")
    private int database;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(password.isEmpty() ? null : password)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(10)
                .setConnectionPoolSize(64);

        RedissonClient redissonClient = Redisson.create(config);

        // 异步初始化布隆过滤器（不阻塞启动）
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(2000); // 等待 Redis 连接稳定

                // 检查布隆过滤器是否已存在
                var bloomFilter = redissonClient.getBloomFilter("bloom:shop:id");
                if (!bloomFilter.isExists()) {
                    // 不存在才初始化
                    bloomFilter.tryInit(1_000_000, 0.01);
                    log.info("✅ 后台异步初始化布隆过滤器完成");
                } else {
                    log.info("✅ 布隆过滤器已存在，无需初始化");
                }
            } catch (Exception e) {
                log.error("❌ 布隆过滤器初始化失败", e);
            }
        });

        return redissonClient;
    }
}
