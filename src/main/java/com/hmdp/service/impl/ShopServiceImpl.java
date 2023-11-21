package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    //缓存重建线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据商品id查询商品信息
     *基于互斥锁解决缓存击穿问题，基于创建空对象解决缓存穿透问题
     * @param id
     * @return
     */
    @Override
    @Transactional
    public Result queryById(Long id) {
        Shop shop = cacheClient.handleCachePenetration(SystemConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById,
                SystemConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }





    /**
     * 根据商铺key值从缓存中查询数据
     * @param key
     * @return
     */
    /*private Result getShopFromCache(String key){
        String shopJson= stringRedisTemplate.opsForValue().get(key);
        //查询缓存是否为空
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = BeanUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        //判断缓存是否为空字符串，如果为空字符串说明是之前存的空对象
        if("".equals(shopJson)){
            return Result.fail("店铺信息为空");
        }

        //缓存为null,说明缓存数据不存在，需要加锁重建
        return null;


    }*/

    /**
     * 保存商品信息到缓存中
     * @param id
     * @param expireSeconds
     */
    /*public void saveShopForCache(Long id,Long expireSeconds){
        //从数据库中查询店铺信息
        Shop shop = getById(id);
        //封装逻辑过期数据
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //将逻辑过期数据存入redis
        stringRedisTemplate.opsForValue().set(SystemConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }*/



    /**
     * 修改店铺信息
     * 先更新数据库中的信息，然后在删除redis中的信息
     * 下次查询时先查数据库中的信息然后存入缓存
     * @param shop
     * @return
     */
    @Override
    @Transactional//声明式事务
    public Result updateShop(Shop shop) {
        //获取店铺id
        Long id = shop.getId();

        //判断id时是否为空
        if(id==null){
            return Result.fail("店铺id为空");
        }

        //更新数据库中的信息
        boolean isUpdate = updateById(shop);
        if(!isUpdate){
            //数据库更新失败，抛出异常
            throw new RuntimeException("数据库更新失败");
        }

        //数据库更新成功，删除缓存，等下次访问的时候在存入缓存
        Boolean isDelete = stringRedisTemplate.delete(SystemConstants.CACHE_SHOP_KEY + id);

        //删除失败，抛出异常
        if(!isDelete){
            throw new RuntimeException("redis删除数据失败");
        }

        return Result.ok();


    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要根据坐标查询
        if(x==null||y==null){
            //不需要坐标查询，按数据库查询
            Page<Shop> page = query().eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;//目标页起始下标
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;//目标页结束下标（不包括）
        //查询redis按照距离排序，分页
        String key=SystemConstants.SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().
                search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        //解析出id
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> result = results.getContent();
        if(result.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        //截取from 到end部分
        List<Long> ids = new ArrayList<>(result.size());
        Map<String,Distance> distanceMap=new HashMap<>(result.size());
        result.stream().forEach(res->{
            //获取店铺id
            String id = res.getContent().getName();
            ids.add(Long.valueOf(id));
            //获取距离
            Distance distance = res.getDistance();
            distanceMap.put(id, distance);
        });
        //根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field (id," + idStr + ")").list();
        for (Shop shop : shops) {
            //为每条店铺信息注入距离
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
