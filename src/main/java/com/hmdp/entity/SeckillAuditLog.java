package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tb_seckill_audit_log")
public class SeckillAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
