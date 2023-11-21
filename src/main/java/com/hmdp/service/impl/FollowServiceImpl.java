package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        if(isFollow){
            //用户未关注，关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean success = this.save(follow);//存入数据库
            if(success){
                //用户id作为key,关注对象id作为value,存入redis的set集合
                stringRedisTemplate.opsForSet().add("follow:" + userId, followUserId.toString());
            }

        }else{
            //用户已关注，取关
            boolean success = this.remove(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, followUserId).eq(Follow::getUserId, userId));
            if(success){
                stringRedisTemplate.opsForSet().remove("follow:" + userId, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        int count = this.count(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, id).eq(Follow::getUserId, userId));
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key1="follow:"+id;
        String key2 = "follow:" + userId;
        //查询当前用户与目标用户共同关注列表
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect==null||intersect.isEmpty()){//一定要加isEmpty()
            //没有共同关注，返回空列表
            return Result.ok(Collections.emptyList());
        }
        //将String类型的id转为Long类型，并且转为list集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //根据id查询用户，转为userDto
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
