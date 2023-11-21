package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @version 1.0
 * @Author moresuo
 * @Date 2023/9/26 21:16
 * @注释
 */
@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    /**
     * 将数据存入redis,并设置有效期
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 将数据加入redis,并设置逻辑过期时间
     * @param key
     * @param value
     * @param timeOut
     * @param unit
     */
    public void setWithLogicalExpire(String key,Object value,Long timeOut,TimeUnit unit){
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(timeOut)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据id查询数据，解决缓存穿透问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param timeOut
     * @param unit
     * @return
     * @param <T>
     * @param <ID>
     */
    public <T,ID> T handleCachePenetration(String keyPrefix, ID id, Class<T> type, Function<ID,T> dbFallback,Long timeOut,TimeUnit unit){
        String key=keyPrefix+id;
        //从redis中查询信息
        String jsonStr = stringRedisTemplate.opsForValue().get(key);

        //判断缓存是否命中
        if(StrUtil.isNotBlank(jsonStr)){
            //缓存命中，返回数据
            T t = JSONUtil.toBean(jsonStr, type);
            return t;
        }
        //缓存未命中检查当前数据是否为空字符串
        if("".equals(jsonStr)){
            //空字符串，说明之前存入过空对象，返回错误信息
            return null;
        }
        //当前数据不在缓存中，就从数据库中查找
        T t = dbFallback.apply(id);
        //判断数据库中是否存在
        if(t==null){
            //不存在，在缓存中存入空对象
            this.set(key, "", SystemConstants.CACHE_NULL_TTL, TimeUnit.SECONDS);
            //返回错误信息
            return null;
        }
        //存在的话就重建缓存
        this.set(key, t, timeOut, unit);
        return t;
    }

    //缓存重建线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询数据，解决缓存击穿问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param timeOut
     * @param unit
     * @return
     * @param <T>
     * @param <ID>
     */
    public <T,ID> T handleCacheBreakdown(String keyPrefix,ID id,Class<T> type,Function<ID,T> dbFallback,Long timeOut,TimeUnit unit){
        String key=keyPrefix+id;
        //从缓存中查询数据
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否命中
        if(StrUtil.isBlank(jsonStr)){
            //缓存未命中，直接返回失败信息
            //为什么不去查询数据库中内容
           return null;
        }
        //缓存命中，将json字符串反序列化为对象
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        //先要转化为JsonObject,然后在反序列化为具体对象
        JSONObject data = (JSONObject) redisData.getData();
        T t = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //没有过期，返回商品
            return t;
        }

        //过期，获取互斥锁，重建缓存
        boolean isLock = tryLock(SystemConstants.LOCK_SHOP_KEY + id);
        if(isLock){
            //获取锁成功，开启线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    T t1 = dbFallback.apply(id);
                    //将数据重建回缓存
                    this.setWithLogicalExpire(key,t1,timeOut,unit);
                }finally {
                    //释放锁
                    unLock(SystemConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        //获取锁失败，说明有其他线程正在重建缓存，我们在一次判断时间是否过期，在多线程的情况下是很有必要的
        jsonStr = stringRedisTemplate.opsForValue().get(key);
        //不用在判断是否命中
        redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        t = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        expireTime = redisData.getExpireTime();
        //再一次判断时间是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return t;
        }
        //过期直接返回过期数据
        return t;
    }

    /**
     * 根据lock的key值获创建锁，如果锁已存在，就创建失败
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", SystemConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        //因为flag是包装类型，拆箱要判断是否为空，为空返回false
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 根据锁的key值销毁锁
     * @param key
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
