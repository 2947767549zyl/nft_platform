package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * 基于 Redisson 的分布式布隆过滤器（简化版）
 */
@Component
@Slf4j
public class BloomFilterClient {

    @Resource
    private RedissonClient redissonClient;

    private RBloomFilter<String> bloomFilter;

    private static final String BLOOM_FILTER_KEY = "bloom:shop:id";

    /**
     * 预期容量：100 万
     */
    private static final long EXPECTED_INSERTIONS = 1_000_000;

    /**
     * 误判率：1%
     */
    private static final double FPP = 0.01;

    /**
     * 启动时初始化布隆过滤器
     */
    @PostConstruct
    public void init() {
        this.bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_KEY);

        // 如果不存在则创建
        if (!bloomFilter.isExists()) {
            bloomFilter.tryInit(EXPECTED_INSERTIONS, FPP);
            log.info("✅ 新建布隆过滤器：{}", BLOOM_FILTER_KEY);
        } else {
            log.info("✅ 复用已存在的布隆过滤器：{}", BLOOM_FILTER_KEY);
        }
    }

    /**
     * 添加单个元素
     */
    public boolean add(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        return bloomFilter.add(value);
    }

    /**
     * 批量添加元素
     */
    public void addAll(Iterable<String> values) {
        if (values == null) {
            return;
        }
        int count = 0;
        for (String value : values) {
            bloomFilter.add(value);
            count++;
        }
        log.info("✅ 批量加载 {} 个元素到布隆过滤器", count);
    }

    /**
     * 判断元素是否存在
     */
    public boolean contains(String value) {
        if (value == null) {
            return false;
        }
        return bloomFilter.contains(value);
    }

    /**
     * 获取元素数量
     */
    public long count() {
        return bloomFilter.count();
    }
}
