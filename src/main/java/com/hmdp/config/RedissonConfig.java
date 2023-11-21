package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @version 1.0
 * @Author moresuo
 * @Date 2023/10/8 17:20
 * @注释
 */
@Configuration
public class RedissonConfig {
    @Value("${spring.redis.host}")
    private String host;//redis主机地址
    @Value("${spring.redis.port}")
    private String port;//redis端口号
    @Value("${spring.redis.password}")
    private String password;//redis密码


    /**
     * 创建redisson配置对象交给IoC管理
     * @return
     */
    @Bean
    public RedissonClient redissonClient(){
        //获取redisson配置对象
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + this.host + ":" + this.port).setPassword(this.password);
        //获取redissonClient对象交给IoC管理
        return Redisson.create(config);
    }
}
