package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //用工具类校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不正确");
        }

        //用工具类生成6位随机验证码
        String code = RandomUtil.randomNumbers(6);

        //将验证码保存到redis当中,并设置有效期
        stringRedisTemplate.opsForValue().set(SystemConstants.LOGIN_CODE_KEY+phone,code,SystemConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //发送验证码
        log.debug("发送验证码：{}", code);

        //返回结果
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone=loginForm.getPhone();
        String code=loginForm.getCode();
        //校验手机号是否正确
        if(RegexUtils.isPhoneInvalid(phone)){
            //手机号不正确
            return Result.fail("手机号格式不正确");
        }

        //判断验证码是否正确
        //从redis中获取验证码信息
        String redisCode = stringRedisTemplate.opsForValue().get(SystemConstants.LOGIN_CODE_KEY + phone);
        if(code==null||!code.equals(redisCode)){
            //验证码错误
            return Result.fail("验证码错误");
        }

        //根据手机号查询用户
        User user = this.query().eq("phone",phone).one();

        //用户不存在，添加用户保存到数据库
        if(user==null){
            user=createUserWithPhone(phone);
        }

        //将用户保存到redis
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将userDto转为map对象，以hashMap的结构对的方式保存在redis中
        //并且要将userDto中的字段属性全部转为字符串对象
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).
                setFieldValueEditor((filedName, filedValue) -> filedValue.toString()));
        //setIgnoreNullValue():自动忽略属性为空的值
        //setFieldValueEditor():自定义字段属性
        //设置登录令牌token
        String token = UUID.randomUUID().toString(true);
        stringRedisTemplate.opsForHash().putAll(SystemConstants.LOGIN_USER_KEY + token, userMap);
        //设置有效期
        stringRedisTemplate.expire(SystemConstants.LOGIN_USER_KEY + token, SystemConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);

    }

    @Override
    public Result sign() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = SystemConstants.USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月第几天
        int day = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        return Result.ok();
    }

    /**
     * 根据手机号创建用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone){
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        this.save(user);
        return user;
    }

    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        int day = now.getDayOfMonth();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = SystemConstants.USER_SIGN_KEY + userId + keySuffix;
        //获取本月到今天为止的签到记录，返回的是一个十进制数
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().
                        get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0)
        );
        if(result==null||result.isEmpty()){
            return Result.ok(0);//没有任何签到记录
        }
        //获取本月签到次数
        Long num = result.get(0);
        if(num==0){
            return Result.ok(0);
        }
        //循环遍历，获取连续签到次数
        int count=0;
        while(true){
            if((num&1)==0){
                break;
            }else{
                count++;
            }
            num >>>=1;//右移一位
        }
        return Result.ok(count);

    }
}
