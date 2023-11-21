-- 优惠券id
local voucherId = ARGV[1];
-- 用户id
local userId = ARGV[2];
-- 订单id
local orderId=ARGV[3]
-- 库存的key
local stockKey = 'seckill:stock:' .. voucherId;
-- 订单key
local orderKey = 'seckill:order:' .. voucherId;
-- 判断库存是否充足 get stockKey > 0 ?
local stock = redis.call('GET', stockKey);
if (tonumber(stock) <= 0) then
    -- 库存不足，返回1
    return 1;
end
-- 库存充足，判断用户是否已经下过单 SISMEMBER orderKey userId
if (redis.call('SISMEMBER', orderKey, userId) == 1) then
    -- 用户已下单，返回2
    return 2;
end
-- 库存充足，没有下过单，扣库存、下单
redis.call('INCRBY', stockKey, -1);
redis.call('SADD', orderKey, userId);
-- 发送消息到队列中
redis.call("XADD","stream.orders","*","userId",userId,"voucherId",voucherId,"id",orderId)
-- 返回0，标识下单成功
return 0;