package com.hmdp.aspect;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import com.hmdp.annotation.RateLimit;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 限流切面，使用Guava RateLimiter实现令牌桶限流
 * 基于用户ID的限流，为每个用户单独创建RateLimiter实例
 * 使用Guava Cache自动管理内存，防止内存泄漏
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    // 使用 Guava Cache 自动清理，防止内存泄漏
    // Key: 方法签名, Value: (Key: 用户ID, Value: RateLimiter)
    private final Map<String, Cache<Long, RateLimiter>> rateLimiterCacheMap = new ConcurrentHashMap<>();



    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 1. 生成方法唯一 Key
        String methodKey = method.getDeclaringClass().getName() + "." + method.getName();

        // 2. 【关键】直接获取用户 ID，不需要 try-catch，因为能到这里的必然已登录
        Long userId = UserHolder.getUser().getId();
        // 防御性编程：万一架构变了导致这里为空，直接抛异常或拒绝，不要静默放行
        if (userId == null) {
            log.error("Unexpected error: User not found in RateLimitAspect for method {}", methodKey);
            return Result.fail("系统错误，请重新登录");
        }

        // 3. 获取或创建该方法的 Cache 容器 (带自动过期)
        Cache<Long, RateLimiter> userCache = rateLimiterCacheMap.computeIfAbsent(methodKey, k ->
                CacheBuilder.newBuilder()
                        .expireAfterAccess(10, TimeUnit.MINUTES) // 5分钟无访问自动清理
                        .build()
        );

        // 4. 获取或创建当前用户的限流器
        RateLimiter rateLimiter = userCache.get(userId, () ->
                RateLimiter.create(rateLimit.permitsPerSecond(), rateLimit.burstCapacity(), TimeUnit.SECONDS)
        );

        // 5. 尝试获取令牌
        if (!rateLimiter.tryAcquire()) {
            log.warn("🚫 限流拦截：用户={}, 接口={}, 限制QPS={}", userId, methodKey, rateLimit.permitsPerSecond());
            return Result.fail(rateLimit.message());
        }

        // 6. 放行
        return joinPoint.proceed();
    }


    //下面是基于redis和lua脚本的限流
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    //定义redisscript脚本对象，泛型为long，返回值类型为long
    private DefaultRedisScript<Long> redisScript;
    /**
     * 初始化方法：在 Bean 创建后加载 Lua 脚本
     */
    @PostConstruct
    public void init() {
        // 1. 创建 ClassPathResource 指向 src/main/resources/ratelimit.lua
        ClassPathResource resource = new ClassPathResource("ratelimit.lua");

        // 2. 初始化 DefaultRedisScript
        redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(resource); // 设置脚本位置
        redisScript.setResultType(Long.class); // 设置返回类型

        log.info("✅ 分布式限流 Lua 脚本加载成功: {}", resource.getPath());
    }
    /*
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 1. 生成方法唯一 Key
        String methodKey = method.getDeclaringClass().getName() + "." + method.getName();

        // 2. 获取用户 ID
        Long userId = UserHolder.getUser().getId();
        if (userId == null) {
            log.error("Unexpected error: User not found in RateLimitAspect for method {}", methodKey);
            return Result.fail("系统错误，请重新登录");
        }

        // ==========================================
        // 【新方案】Redis + Lua 分布式限流
        // ==========================================

        // 构建全局唯一的 Redis Key: limit:接口名:用户ID
        String redisKey = "limit:" + methodKey + ":" + userId;

        long currentTime = System.currentTimeMillis();
        int maxCount = rateLimit.permitsPerSecond();
        int windowSize = 1000; // 窗口大小：1000 毫秒 (1秒)

        try {
            // 执行 Lua 脚本
            Long result = stringRedisTemplate.execute(
                    redisScript,
                    Collections.singletonList(redisKey),
                    String.valueOf(maxCount),
                    String.valueOf(windowSize),
                    String.valueOf(currentTime)
            );

            if (result != null && result == 1) {
                return joinPoint.proceed(); // 放行
            } else {
                log.warn("🚫 [分布式限流] 拦截：用户={}, 接口={}, 限制QPS={}", userId, methodKey, maxCount);
                return Result.fail(rateLimit.message());
            }

        } catch (Exception e) {
            // ==========================================
            // 【降级策略】Redis 异常处理
            // ==========================================
            log.error("⚠️ Redis 限流服务异常，触发降级保护！用户={}, 接口={}", userId, methodKey, e);

            // 策略：为了安全，直接拒绝 (Fail-Close)
            // 也可以在这里切换回 Guava 本地限流逻辑 (如果需要)
            return Result.fail("系统繁忙，请稍后再试 (限流服务异常)");
        }
    }

     */

}