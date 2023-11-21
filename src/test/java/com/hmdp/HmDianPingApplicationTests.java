package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.SystemConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Test
    public void test01(){
        //获取店铺数据
        List<Shop> shopList = shopService.list();
        //根据typeId进行分类
        Map<Long, List<Shop>> shopMap = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //将分好类的店铺写入redis
        for(Map.Entry<Long,List<Shop>> entry:shopMap.entrySet()){
            //获取typeId
            Long typeId = entry.getKey();
            String key= SystemConstants.SHOP_GEO_KEY+typeId;
            //获取店铺信息
            List<Shop> shops = entry.getValue();
            //批量写入
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>();
            for (Shop shop : shops) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

}
