package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.KafkaException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLTimeoutException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ========== 1. 业务与数据异常 ==========

    @ExceptionHandler(DuplicateKeyException.class)
    public Result handleDuplicateKey(DuplicateKeyException e) {
        log.warn("⚠️ 唯一索引冲突（重复购买/注册）: {}", e.getMessage());
        return Result.fail( "您已经购买过了，请勿重复提交");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("⚠️ 数据完整性异常: {}", e.getMessage());
        return Result.fail( "提交的数据格式有误");
    }

    // ========== 2. 参数校验异常 ==========

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldError().getDefaultMessage();
        log.warn("⚠️ 参数校验失败: {}", msg);
        return Result.fail( msg);
    }

    @ExceptionHandler(BindException.class)
    public Result handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldError().getDefaultMessage();
        log.warn("⚠️ 参数绑定失败: {}", msg);
        return Result.fail( msg);
    }

    // ========== 3. 中间件异常（降级处理） ==========

    @ExceptionHandler({RedisConnectionFailureException.class, RedisSystemException.class})
    public Result handleRedisException(Exception e) {
        log.error("❌ Redis 服务异常，触发降级: {}", e.getMessage());
        return Result.fail( "系统繁忙，请稍后再试");
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, SQLTimeoutException.class, QueryTimeoutException.class})
    public Result handleDbException(Exception e) {
        log.error("❌ 数据库服务异常: {}", e.getMessage());
        return Result.fail( "数据库繁忙，请稍后再试");
    }

    @ExceptionHandler(DeadlockLoserDataAccessException.class)
    public Result handleDeadlock(DeadlockLoserDataAccessException e) {
        log.warn("⚠️ 数据库死锁: {}", e.getMessage());
        return Result.fail( "系统繁忙，请点击重试");
    }

    @ExceptionHandler(KafkaException.class)
    public Result handleKafkaException(KafkaException e) {
        log.error("❌ Kafka 消息服务异常: {}", e.getMessage());
        return Result.fail( "订单处理延迟，请稍后查询订单状态");
    }

    // ========== 4. 兜底异常 ==========

    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        log.error("💥 系统未知异常: ", e); // 打印堆栈
        return Result.fail( "系统内部错误，请联系管理员");
    }
}
