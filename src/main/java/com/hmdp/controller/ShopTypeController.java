package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @GetMapping("list")
    public Result queryTypeList() {
        // 1、从Redis中查询店铺类型
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY + UUID.randomUUID().toString();
        String shopTypeJSON = stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(shopTypeJSON)){
            //在redis中直接查询到了
            return Result.ok(JSONUtil.toList(shopTypeJSON, ShopType.class));
        }
        //缓存未命中，查询数据库
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        //判断数据库中是否查询得到
        if(Objects.isNull(typeList)) {
            return Result.fail("数据库中无店铺类型数据");
        }
        //有数据的话，把数据写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList), RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
