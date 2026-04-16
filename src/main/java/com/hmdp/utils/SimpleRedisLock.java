package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.yaml.snakeyaml.events.Event;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {


    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeout) {

        //获取当前线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //等同于redis语法中的setnx
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

//    @Override
//    public void unlock() {
//        //先获取线程标识，判断与redis锁中的线程标识是否一致
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        String currentValue = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (threadId.equals(currentValue)) {
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }

    //现在基于lua脚本释放锁
    public void unlock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String scriptText =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "   return redis.call('del', KEYS[1]) " +
                        "else " +
                        "   return 0 " +
                        "end";

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(scriptText, Long.class);

        stringRedisTemplate.execute(
                script,
                Collections.singletonList(KEY_PREFIX + name),
                threadId
        );
    }
}
