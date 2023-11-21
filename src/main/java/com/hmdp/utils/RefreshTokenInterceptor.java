package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @version 1.0
 * @Author moresuo
 * @Date 2023/9/23 12:32
 * @注释 不做拦截功能，只做保存用户信息和刷新token功能
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {
    //new出来的对象不会放入IoC容器，需要手动构造传参
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取token,并判断token是否存在
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return true;
        }

        //判断用户是否存在
        //从redis中获取用户
        Map<Object, Object> user = stringRedisTemplate.opsForHash().entries(SystemConstants.LOGIN_USER_KEY + token);
        if(user.isEmpty()){
            //用户还未登录，不需要刷新，直接放行
            return true;
        }

        //用户存在，将用户保存到ThreadLocal中
        //将map集合转为bean
        UserDTO userDTO = BeanUtil.fillBeanWithMap(user, new UserDTO(), false);
        UserHolder.saveUser(userDTO);

        //刷新有效期
        stringRedisTemplate.expire(SystemConstants.LOGIN_USER_KEY + token, SystemConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
