package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.datetime.DateFormatter;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @version 1.0
 * @Author moresuo
 * @Date 2023/9/26 23:15
 * @注释
 */
@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //开始时间戳
    public static final long BEGIN_TIMESTAMP=1640995200;
    //序列化位数
    public static final int COUNT_BITS=32;

    /**
     * 生成全局唯一id
     * @param keyPrefix 业务前缀
     * @return
     */
    public long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now=LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //生成序列号，以当天时间戳为key,防止超过序列号位数
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //设置key值然后自增
        Long count = stringRedisTemplate.opsForValue().increment(SystemConstants.ID_PREFIX + keyPrefix + ":" + date);//返回当前自增到多少的值
        //拼接并返回
        //64位保存全局id,第一位是符号位0，永远为正，后31位为时间戳，最后32位为自增值
        return timestamp <<COUNT_BITS | count;//先把时间戳左移32位，然后与count做或运算

    }
}
