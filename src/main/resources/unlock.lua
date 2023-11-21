if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 一致，直接删除
    return redis.call('del', KEYS[1])
end
-- 不一致，返回0
return 0