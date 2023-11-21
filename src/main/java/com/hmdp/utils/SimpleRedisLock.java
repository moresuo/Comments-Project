package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @version 1.0
 * @Author moresuo
 * @Date 2023/10/7 17:01
 * @注释
 */
public class SimpleRedisLock implements Lock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;//业务名称
    private static final String LOCK_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //类加载时动态写入lua脚本一次
    static{
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        //获取lua脚本
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设置返回值类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    
    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate){
        this.name=name;
        this.stringRedisTemplate=stringRedisTemplate;
    }

    /**
     * 利用redis获取锁
     * @param timeOut
     * @return
     */
    @Override
    public boolean tryLock(long timeOut) {
        //获取当前线程id,作为value值
        String id = ID_PREFIX+Thread.currentThread().getId();
        Boolean res = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + name, id , timeOut, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(res);
    }

    /**
     * 释放锁
     */
    @Override
    public void unLock() {
        //lua保证判断锁的唯一标识符和释放锁操作是一个原子性操作
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(LOCK_PREFIX + name),
                ID_PREFIX+Thread.currentThread().getId());
    }
}
