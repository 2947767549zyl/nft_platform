package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private final UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式无效!");
        }
        //2.生成6位随机验证码
        String code = RandomUtil.randomNumbers(6);
        log.info("发送验证码：{}给{}", code, phone);

        //3.保存验证码到Session
        //session.setAttribute("code", code);
        //3.现在优化为保存验证码到redis中，key为电话号码，value为验证码
        stringRedisTemplate.opsForValue().set("login:code:"+phone, code, 3, TimeUnit.MINUTES);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式无效!");
        }
        //2.校验验证码
        //session是一个Map，它存储在服务器端，用于存储用户会话信息
//        if (loginForm.getCode() == null || !loginForm.getCode().equals(session.getAttribute("code"))) {
//            return Result.fail("验证码无效!");
//        }
        //2.校验验证码，优化为从redis中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get("login:code:" + loginForm.getPhone());
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            return Result.fail("验证码无效!");
        }
        //3.验证码一致，则根据手机号查询用户
        //这里使用mybatis-plus来实现
        //User user = userMapper.selectOne(new QueryWrapper<User>().eq("phone", loginForm.getPhone()));
        User user = query().eq("phone", loginForm.getPhone()).one();
        //4.判断用户是否存在，不存在则创建新用户，保存到数据库
        if (user == null) {
            //核心保存信息就是手机号和昵称
            user = createNewUser(loginForm.getPhone());
        }
        //保存用户信息到Session
        //session.setAttribute("user", user);
        //这里进行优化，保存用户信息到redis中，key为随机生成的token，value为用户信息
        //使用uuid生成token
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //采用哈希结构来存储对象数据
        stringRedisTemplate.opsForHash().putAll("login:token:" + token, userMap);
        //设置30分钟的有效期，单位为分钟，也就是说30分钟后token会自动失效，防止用户一直登录
        stringRedisTemplate.expire("login:token:" + token, 30, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createNewUser(String phone) {

        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
