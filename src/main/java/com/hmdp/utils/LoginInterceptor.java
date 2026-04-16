package com.hmdp.utils;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

//登录拦截器，用于拦截未登录的用户
public class LoginInterceptor implements HandlerInterceptor {


    private StringRedisTemplate stringRedisTemplate;

    //这里只能手动创建一个StringRedisTemplate对象，因为Spring容器中没有StringRedisTemplate对象
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    /**
     * 前置拦截器，用于判断用户是否登录
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //从请求头中获取session信息
//        HttpSession session = request.getSession();
//        // 1、判断用户是否存在
//        User user = (User) session.getAttribute("user");
//        if (Objects.isNull(user)){
//            // 用户不存在，直接拦截
//            response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
//            return false;
//        }
//        // 2、用户存在，则将用户信息保存到ThreadLocal中，方便后续逻辑处理
//        // 比如：方便获取和使用用户信息，session获取用户信息是具有侵入性的
//        //这里要求形参是UserDTO
//        UserDTO userDTO = new UserDTO();
//        BeanUtils.copyProperties(user, userDTO);
//        UserHolder.saveUser(userDTO); // Changed from UserHolder.saveUser(user) to UserHolder.saveUser(userDTO)
//
//        return HandlerInterceptor.super.preHandle(request, response, handler);

//        //现在通过redis来判断用户是否登录
//        //首先获取请求头中的token
//        String token = request.getHeader("authorization");
//        if(StrUtil.isBlank(token)){
//            response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
//            return false;
//        }
//        //基于token获取用户信息
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries("login:token:" + token);
//        if (userMap.isEmpty()) {
//            response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
//            return false;
//        }
//        //将userMap转换为UserDTO
//        UserDTO userDTO = new UserDTO();
//        BeanUtil.fillBeanWithMap(userMap, userDTO, false);
//
//        //保存用户信息到ThreadLocal中
//        UserHolder.saveUser(userDTO);
//
//        //刷新token有效期
//        stringRedisTemplate.expire("login:token:" + token, 30, TimeUnit.MINUTES);
//        return true;



        //现在又要优化拦截器
        if(UserHolder.getUser() == null){
            response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
            return false;
        }
        return true;
    }


    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable ModelAndView modelAndView) throws Exception {
        HandlerInterceptor.super.postHandle(request, response, handler, modelAndView);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        //移除用户信息
        UserHolder.removeUser();
    }
}
