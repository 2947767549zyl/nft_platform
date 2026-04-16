package com.hmdp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解，用于标记需要限流的方法
 */
@Target(ElementType.METHOD) // 注解作用在方法上
@Retention(RetentionPolicy.RUNTIME) // 注解保留运行时
public @interface RateLimit {
    
    /**
     * 每秒生成的令牌数
     */
    int permitsPerSecond() default 10;
    
    /**
     * 令牌桶的容量
     */
    int burstCapacity() default 20;
    
    /**
     * 限流失败时的提示信息
     */
    String message() default "请求过于频繁，请稍后再试";
}
