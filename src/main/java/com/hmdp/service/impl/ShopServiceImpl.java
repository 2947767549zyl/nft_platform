package com.hmdp.service.impl;

import cn.hutool.bloomfilter.BloomFilter;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.BloomFilterClient;
import com.hmdp.utils.CaffeineCacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CaffeineCacheClient caffeineCacheClient;
    @Autowired
    private BloomFilterClient bloomFilter;

    /**
     * 启动时预热布隆过滤器：从数据库加载所有存在的店铺 ID
     */
    @PostConstruct
    public void initBloomFilter() {
        try {
            log.info("开始加载布隆过滤器...");
            //list方法是MyBatis-Plus 提供的核心方法，等价于执行 SQL：SELECT * FROM shop;
            List<Shop> allShops = list();//这个list方法会返回所有数据，所以这里需要过滤掉null的数据
            if (allShops != null && !allShops.isEmpty()) {
                List<String> shopIds = allShops.stream()
                        .map(shop -> shop.getId().toString())
                        .collect(Collectors.toList());
                bloomFilter.addAll(shopIds);
                log.info("✅ 布隆过滤器预热完成，共加载 {} 个店铺 ID", shopIds.size());
            }
        } catch (Exception e) {
            log.error("❌ 布隆过滤器预热失败", e);
        }
    }

    @Override
    public Result queryShopById(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // ========== 1. 布隆过滤器预检查（防穿透第一道防线）==========
        if (!bloomFilter.contains(id.toString())) {
            log.debug("🛡️ 布隆过滤器拦截：id={}");
            return Result.fail("商铺不存在");
        }

        //先从本地缓存caffe缓存中获取
        Shop shopFromL1 = caffeineCacheClient.get(key, Shop.class);
        if (shopFromL1 != null){
            return Result.ok(shopFromL1);
        }
        // 1. 如果l1缓存未命中，从 Redis 查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 查redis缓存命中（包括空值），那就可以直接回填给caffeine缓存
        if (shopJson != null) {
            if ("".equals(shopJson)) {
                return Result.fail("商铺不存在");
            }
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            caffeineCacheClient.put(key, shop);
            return Result.ok(shop);
        }

        // 3. 两级缓存都未命中 → 为了防止缓存击穿，尝试获取互斥锁，只允许一个请求获取锁
        String lockKey = "lock:shop:" + id;
        try {
            boolean isLocked = tryLock(lockKey);
            if (!isLocked) {
                // 获取锁失败 → 短暂等待后重试（避免大量请求同时重试，可加随机 sleep）
                Thread.sleep(50);
                return queryShopById(id); // 递归重试
            }

            // 4. 获取锁成功 → 再次检查缓存（double-check），这是为了不让其它请求重复查询数据库
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (shopJson != null) {
                if ("".equals(shopJson)) {
                    return Result.fail("商铺不存在");
                }
                Shop shop = JSONUtil.toBean(shopJson, Shop.class);
                caffeineCacheClient.put(key, shop);//
                return Result.ok(shop);
            }

            // 5. 真正查数据库
            Shop shop = getById(id);
            if (shop == null) {
                // 防穿透：缓存空值
                stringRedisTemplate.opsForValue().set(key, "", CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return Result.fail("商铺不存在");
            }

            // 6. 写入缓存（建议加随机 TTL 防雪崩）
            stringRedisTemplate.opsForValue().set(
                    key,
                    JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL + RandomUtil.randomInt(0, 5),
                    TimeUnit.MINUTES
            );
            //同时缓存到caffeine缓存
            caffeineCacheClient.put(key, shop);

            return Result.ok(shop);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("查询失败");
        } finally {
            // 7. 释放锁
            unlock(lockKey);
        }
    }
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺ID不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        //删除caFFeine
        caffeineCacheClient.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    /**
     * 新增店铺时，添加到布隆过滤器
     */
    @Override
    @Transactional
    public boolean save(Shop entity) {
        boolean saved = super.save(entity);
        if (saved && entity.getId() != null) {
            bloomFilter.add(entity.getId().toString());
        }
        return saved;
    }

}
