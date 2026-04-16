
package com.hmdp.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存工具类
 * 专注于 L1 缓存管理，不依赖 Redis
 */

@Component
@Slf4j
public class CaffeineCacheClient {

    /**
     * L1 缓存：Caffeine 本地缓存
     */
    private Cache<String, Object> cache;

    /**
     * 初始化缓存配置
     */
    @PostConstruct
    public void init() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(10000)           // 最多存储 10000 条
                .expireAfterWrite(2, TimeUnit.MINUTES)  // 写入后 2 分钟过期
                .recordStats()                // 记录统计信息
                .build();

        log.info("✅ Caffeine 本地缓存初始化完成");
    }

    /**
     * 从缓存获取数据
     */
    public <T> T get(String key, Class<T> type) {
        Object value = cache.getIfPresent(key);
        if (value == null) {
            return null;
        }
        // 处理空值标记
        if ("EMPTY".equals(value)) {
            return null;
        }
        return type.cast(value);
    }

    /**
     * 向缓存写入数据
     */
    public void put(String key, Object value) {
        cache.put(key, value);
        log.debug("写入 Caffeine 缓存：key={}", key);
    }

    /**
     * 删除缓存
     */
    public void delete(String key) {
        cache.invalidate(key);
        log.debug("删除 Caffeine 缓存：key={}", key);
    }

    /**
     * 清空所有缓存
     */
    public void clear() {
        cache.invalidateAll();
    }

    /**
     * 打印缓存统计
     */
    public void printStats() {
        log.info("=== Caffeine 缓存统计 ===");
        log.info("当前缓存数量：{}", cache.estimatedSize());
        log.info("命中次数：{}", cache.stats().hitCount());
        log.info("未命中次数：{}", cache.stats().missCount());
        log.info("命中率：{}%", String.format("%.2f", cache.stats().hitRate() * 100));
    }
}