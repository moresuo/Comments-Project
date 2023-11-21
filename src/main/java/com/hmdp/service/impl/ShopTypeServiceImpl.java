package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 查询所有商铺类型
     * @return
     */
    @Override
    public Result queryTypeList() {
        //从redis中查询商铺类型
        String shopTypeJson = stringRedisTemplate.opsForValue().get(SystemConstants.CACHE_TYPE_KEY);

        List<ShopType> shopList=null;
        //判断缓存是否命中
        if(StrUtil.isNotBlank(shopTypeJson)){
            //缓存命中，直接返回
            shopList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopList);
        }

        //缓存未命中，查询数据库
        shopList = query().list();

        //判断数据库中是否存在
        if(shopList==null){
            //不存在，返回失败信息
            return Result.fail("店铺类型不存在");
        }

        //存在，写入redis中
        stringRedisTemplate.opsForValue().set(SystemConstants.CACHE_TYPE_KEY, JSONUtil.toJsonStr(shopList), SystemConstants.CACHE_TYPE_TTL, TimeUnit.MINUTES);

        //返回信息
        return Result.ok(shopList);
    }
}
