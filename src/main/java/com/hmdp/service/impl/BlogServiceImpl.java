package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    /**
     * 根据id查询博客
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = this.getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        //查询博客相关的用户信息，因为博客实体类包含用户的昵称和图片，但不会与数据库表映射
        queryUserByBlog(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 判断当前用户是否点赞
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user==null){
            return;//用户未登录，不用查询点赞功能
        }
        Long userId = blog.getUserId();
        String key=SystemConstants.BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    /**
     * 将传进来的博客对象根据用户id赋上昵称和图片信息
     * @param blog
     */
    private void queryUserByBlog(Blog blog){
        //获取用户id
        Long userId = blog.getUserId();
        //根据用户id获取用户
        User user = userService.getById(userId);
        //为博客对象添值
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog->{
            queryUserByBlog(blog);
            isBlogLiked(blog);

        });
        return Result.ok(records);
    }

    /**
     * 点赞功能
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //判断用户是否点赞
        Long userId = UserHolder.getUser().getId();
        String key=SystemConstants.BLOG_LIKED_KEY;
        Double score = stringRedisTemplate.opsForZSet().score(key + id, userId.toString());
        if(score==null){
            //用户未点赞，点赞数加一
            boolean success = this.update(new LambdaUpdateWrapper<Blog>().eq(Blog::getId, id).setSql("liked=liked+1"));
            if(success){
                //数据库更新成功，保存到缓存中
                stringRedisTemplate.opsForZSet().add(key + id, userId.toString(), System.currentTimeMillis());
            }
        }else{
            //用户已点赞。点赞数减一
            boolean success = this.update(new LambdaUpdateWrapper<Blog>().eq(Blog::getId, id).setSql("liked=liked-1"));
            if(success){
                stringRedisTemplate.opsForZSet().remove(key + id, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询点赞排行榜
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        //查询缓存中排行的前5个用户id
        String key=SystemConstants.BLOG_LIKED_KEY+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());//返回一个空列表
        }
        //将String类型的id转为Long类型，并且要按照时间戳的顺序排序保存在一个list集合中
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //将id按逗号隔开
        String idStr = StrUtil.join(",", ids);
        System.out.println(idStr);
        //要求返回UserDto的列表
        //根据id查询出用户的list集合，再将user转为UserDto
        List<UserDTO> userDTOList = userService.list(new LambdaQueryWrapper<User>().
                        in(User::getId,ids).last("order by field (id,"+idStr+")")).
                stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        //保存笔记
        boolean success = this.save(blog);
        if(!success){
            //保存失败
            return Result.fail("笔记保存失败");
        }
        //将笔记id保存到redis中
        //查询笔记作者所有粉丝
        List<Follow> follows = followService.list(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, userId));
        //将笔记推送给所有粉丝
        for(Follow follow:follows){
            //获取粉丝id
            Long id = follow.getUserId();
            String key="feed:"+id;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        String key="feed:"+userId;
        //查询redis,获取笔记id
        //offset为偏移量，score值可能存在相同（两个博主同时发送消息给用户），可能导致重复显示
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //判断收件箱中是否有数据
        if (typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        //收件箱中有数据
        List<Long> ids = new ArrayList<>(typedTuples.size());//保存笔记id,初始化长度
        long minTime=0;//记录当前最小值
        int os=1;//记录offset,offset为上一次的最小值的个数
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            ids.add(Long.valueOf(typedTuple.getValue()));//将笔记id转为Long并保存在list中
            long score = typedTuple.getScore().longValue();
            if(minTime==score){
                os++;
            }else{
                minTime=score;
                os=1;
            }
        }
        //根据笔记id查询blog,查询默认是根据主键查询，得出的结果也是按主键排序，应该更改为list中的顺序
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = this.list(new LambdaQueryWrapper<Blog>().in(Blog::getId, ids).
                last("order by field(id," + idStr + ")"));
        //设置博客相关的用户信息，是否被点赞等
        for (Blog blog : blogs) {
            queryUserByBlog(blog);
            isBlogLiked(blog);
        }
        //封装并返回
        ScrollResult scrollResult=new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

}
