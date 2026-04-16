package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    //这里只能手动创建一个StringRedisTemplate对象，因为Spring容器中没有StringRedisTemplate对象
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        //移除用户信息
        UserHolder.removeUser();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //首先获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            //response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
            return true;
        }
        //基于token获取用户信息
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries("login:token:" + token);
        if (userMap.isEmpty()) {
            // 用户不存在，说明当前用户未登录，不需要刷新直接放行
            return true;
       }
        //将userMap转换为UserDTO
        UserDTO userDTO = new UserDTO();
        BeanUtil.fillBeanWithMap(userMap, userDTO, false);

        //保存用户信息到ThreadLocal中
        UserHolder.saveUser(userDTO);

//        //刷新token有效期
        stringRedisTemplate.expire("login:token:" + token, 3000, TimeUnit.MINUTES);
        return true;
    }
}
