package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import lombok.var;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissionClient() {
        // 配置类
        Config config = new Config();

        // 添加 Redis 地址，此处添加了单点的地址，也可以使用 config.useClusterServers() 添加集群地址
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");

        // 创建客户端
        return Redisson.create(config);
        //RedissonClient redissonClient = Redisson.create(config);


    }

}
