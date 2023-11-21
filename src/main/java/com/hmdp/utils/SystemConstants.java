package com.hmdp.utils;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = "D:\\nginx-1.18.0\\nginx-1.18.0\\html\\hmdp\\imgs";//图片路径

    public static final String USER_NICK_NAME_PREFIX = "user_";//昵称前缀

    public static final int DEFAULT_PAGE_SIZE = 5;//默认每页大小

    public static final int MAX_PAGE_SIZE = 10;//每页最大大小

    public static final String LOGIN_CODE_KEY="login:code:";//在redis中保存验证码的key的前缀

    public static final Long LOGIN_CODE_TTL=1L;//验证码有效期

    public static final String LOGIN_USER_KEY="login:user:";//在redis中保存用户信息的key的前缀

    public static final Long LOGIN_USER_TTL=30L;//用户有效期

    public static final Long CACHE_NULL_TTL=2L;//空值有效期

    public static final String CACHE_SHOP_KEY="cache:shop:";//在redis中保存的店铺信息的key的前缀

    public static final Long CACHE_SHOP_TTL=30L;//店铺保存信息有效期

    public static final String CACHE_TYPE_KEY="cache:type:";//在redis中保存的商铺类型的key的前缀

    public static final Long CACHE_TYPE_TTL=30L;//店铺类型有效期

    public static final String LOCK_SHOP_KEY="lock:shop:";//缓存重建的锁的key的前缀

    public static final Long LOCK_SHOP_TTL=10L;//锁的有效期

    public static final String ID_PREFIX="icr";//全局id自增key的前缀

    public static final String SECKILL_STOCK_KEY="seckill:stock:";//秒杀卷库存前缀

    public static final String BLOG_LIKED_KEY="blog:liked:";//博客点赞前缀

    public static final String SHOP_GEO_KEY="shop:geo:";//店铺地理位置前缀

    public static final String USER_SIGN_KEY = "sign:";



}
