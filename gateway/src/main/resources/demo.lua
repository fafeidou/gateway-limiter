-- token的key
local tokens_key = KEYS[1]
-- 时间戳的key
local timestamp_key = KEYS[2]
--redis.log(redis.LOG_WARNING, "tokens_key " .. tokens_key)

-- 往令牌桶里面放令牌的速率，一秒多少个
local rate = tonumber(ARGV[1])
-- 令牌桶的数量
local capacity = tonumber(ARGV[2])
-- 当前的时间戳
local now = tonumber(ARGV[3])
-- 请求令牌的数量
local requested = tonumber(ARGV[4])

-- 放满令牌桶是的时长
local fill_time = capacity/rate
-- redis 过期时间 这里为什么是放满令牌桶的两倍
local ttl = math.floor(fill_time*2)

--redis.log(redis.LOG_WARNING, "rate " .. ARGV[1])
--redis.log(redis.LOG_WARNING, "capacity " .. ARGV[2])
--redis.log(redis.LOG_WARNING, "now " .. ARGV[3])
--redis.log(redis.LOG_WARNING, "requested " .. ARGV[4])
--redis.log(redis.LOG_WARNING, "filltime " .. fill_time)
--redis.log(redis.LOG_WARNING, "ttl " .. ttl)
-- 获取令牌桶的数量，如果为空，将令牌桶容量赋值
local last_tokens = tonumber(redis.call("get", tokens_key))
if last_tokens == nil then
    last_tokens = capacity
end
--redis.log(redis.LOG_WARNING, "last_tokens " .. last_tokens)
-- 获取最后的更新时间戳，如果为空，设置为0
local last_refreshed = tonumber(redis.call("get", timestamp_key))
if last_refreshed == nil then
    last_refreshed = 0
end
--redis.log(redis.LOG_WARNING, "last_refreshed " .. last_refreshed)
-- 计算出时间间隔
local delta = math.max(0, now-last_refreshed)
-- 该往令牌桶放令牌的数量
local filled_tokens = math.min(capacity, last_tokens+(delta*rate))
-- 看剩余的令牌是否能够获取到
local allowed = filled_tokens >= requested
local new_tokens = filled_tokens
-- 零代表false
local allowed_num = 0
-- 如果允许获取得到，计算出剩余的令牌数量，并标记可以获取
if allowed then
    new_tokens = filled_tokens - requested
    allowed_num = 1
end

--redis.log(redis.LOG_WARNING, "delta " .. delta)
--redis.log(redis.LOG_WARNING, "filled_tokens " .. filled_tokens)
--redis.log(redis.LOG_WARNING, "allowed_num " .. allowed_num)
--redis.log(redis.LOG_WARNING, "new_tokens " .. new_tokens)
-- 存到redis
redis.call("setex", tokens_key, ttl, new_tokens)
redis.call("setex", timestamp_key, ttl, now)
--  lua可以返回多个字段，java获取时用List获取
return { allowed_num, new_tokens }
