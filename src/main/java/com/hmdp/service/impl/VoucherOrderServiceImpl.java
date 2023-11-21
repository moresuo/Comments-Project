package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);//存储订单的阻塞队列

    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();//线程池创建子线程去创建订单

    private IVoucherOrderService proxy;//代理对象

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;//加载lua脚本
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct//类加载完成立马执行该方法
    private void init() {
// 执行线程任务
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //不断从阻塞队列中获取订单
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try{
                    //获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    //判断消息是否获取成功
                    if(list==null||list.isEmpty()){
                        continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //如果成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                }catch (Exception e){
                    //没有被确认
                    handlePendingList();
                }

            }
        }

        private void handlePendingList() {
            while(true){
                try{
                    //获取appendingList中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0")));
                    //m没有读取到，说明确认成功
                    if(list==null||list.isEmpty()){
                        break;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //如果成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                }catch (Exception e){
                    throw new RuntimeException(e);
                }

            }
        }
    }

    /**
     * 创建订单
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获取锁失败
            return;
        }
        try {
            //获取锁成功，创建订单，需要代理对象调用，保证事务有效
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }

    }
    public Result secKillVoucher(Long voucherId){
        //创建订单id
        Long orderId = redisIdWorker.nextId("order");
        //执行lua脚本，判断是否有秒杀资格，并把订单信息存入消息队列
        Long result=null;
        try {
            result=stringRedisTemplate.execute(SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    UserHolder.getUser().getId().toString(),
                    orderId.toString());
        }catch (Exception e){
            throw new RuntimeException(e);
        }
        //如果result不为0，说明不具有秒杀资格
        if(result==null||result!=0L){
            int r=result.intValue();
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //在主线程中创建代理对象，子线程可访问，代理对象存储在堆区，数据共享
        proxy= (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);

    }
    /**
     * 抢购秒杀卷，异步执行
     * @param
     * @return
     */
    /*@Transactional
    public Result secKillVoucher(Long voucherId){
        //执行lua脚本，判断是否有秒杀资格
        Long result=null;
        try {
             result= stringRedisTemplate.execute(SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    UserHolder.getUser().getId().toString());
        }catch (Exception e){
            throw new RuntimeException(e);
        }
        //如果result不为0，说明不具有秒杀资格
        if(result==null||result!=0L){
            int r=result.intValue();
            return Result.fail(r == 1 ? "库存不足" : "用户不能重复下单");
        }
        //result为0，表示具有秒杀资格，将订单信息存入到阻塞队列，实现异步下单
        long orderId = redisIdWorker.nextId("order");
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //将订单存入阻塞队列
        orderTasks.add(voucherOrder);
        //在主线程中创建代理对象，子线程可访问，代理对象存储在堆区，数据共享
        proxy= (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);

    }*/

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //判断当前用户是否是第一单
        int count = this.count(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getUserId, userId).eq(VoucherOrder::getVoucherId, voucherId));
        if(count>=1){
            //当前用户不是第一单
            return;
        }
        //库存减一
        /*seckillVoucherService.update(new LambdaQueryWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId,voucherId).
                gt(SeckillVoucher::getStock,0)
                .setSql)*/
        boolean flag = seckillVoucherService.update(new LambdaUpdateWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, voucherId).
                gt(SeckillVoucher::getStock,0).
                setSql("stock=stock-1"));
        if(!flag){
            throw new RuntimeException("秒杀卷扣减失败");
        }
        //将订单保存到数据库
        flag = this.save(voucherOrder);
        if(!flag){
            throw new RuntimeException("订单保存失败");
        }

    }


    /**
     * 抢购秒杀卷
     * @param voucherId
     * @return
     */
    /*@Override
    @Transactional
    public Result secKillVoucher(Long voucherId) {
        //查询秒杀卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //不需要判断秒杀卷是否存在，因为voucherId是从前端获取的
        //判断是否在抢购时间之前
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //判断是否在抢购时间之后
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("抢购时间结束");
        }
        //判断库存是否小于一
        if(voucher.getStock()<1){
            return Result.fail("库存已清空");
        }
        Long id = UserHolder.getUser().getId();//获取用户id
        //以用户的id作为锁
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + id, stringRedisTemplate);
        RLock lock = redissonClient.getLock("order:" + id);
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获取锁失败
            return Result.fail("一人只能下一单");
        }
        //获取锁成功
        try {
            //创建代理对象，防止事务失效
            IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();//释放锁
        }



    }

    *//**
     * 创建订单
     * @param voucherId
     * @return
     *//*
    @Transactional
    public Result createVoucherOrder(Long voucherId){
        //判断用户是否已经抢购过这张秒杀卷
        Long id = UserHolder.getUser().getId();//获取用户id
        int count = this.count(new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, voucherId).eq(VoucherOrder::getUserId, id));
        if(count>0){
            //该用户抢购过这张秒杀卷
            return Result.fail("用户已抢购");
        }
        //抢购成功，库存减一
        //使用cas乐观锁，在更新之前比较库存是否不为空
        boolean flag = seckillVoucherService.update(new LambdaUpdateWrapper<SeckillVoucher>().eq(SeckillVoucher::getVoucherId, voucherId).
                gt(SeckillVoucher::getStock,0).
                setSql("stock=stock-1"));
        if(!flag){
            return Result.fail("抢购失败");
        }
        //秒杀成功，建立对应订单保存到数据库
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(id);
        //生成全局唯一id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //保存到数据库
        flag = this.save(voucherOrder);
        if(!flag){
            return Result.fail("创建订单失败");
        }
        //返回订单编号
        return Result.ok(orderId);
    }*/
}
