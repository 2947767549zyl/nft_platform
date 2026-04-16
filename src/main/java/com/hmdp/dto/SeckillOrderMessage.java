package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀订单消息 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单 ID
     */
    private Long orderId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 优惠券 ID
     */
    private Long voucherId;

    /**
     * 时间戳
     */
    private Long timestamp;
}
