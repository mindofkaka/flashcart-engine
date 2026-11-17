-- KEYS[1] = bucket key
-- ARGV[1] = capacity, ARGV[2] = refill tokens/second, ARGV[3] = cost
-- Returns {allowed (1/0), remaining tokens, retry_after_ms}

local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local cost     = tonumber(ARGV[3])

-- Redis clock, so app instances with skewed clocks can't cheat
local t   = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)

local data   = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil then          -- first request: full bucket
    tokens = capacity
    ts = now
end

-- refill based on elapsed time
local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + elapsed * rate / 1000)

local allowed  = 0
local retry_ms = 0
if tokens >= cost then
    tokens  = tokens - cost
    allowed = 1
else
    retry_ms = math.ceil((cost - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', string.format('%.0f', now))
-- idle buckets expire: 2x the time needed to refill fully
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000) * 2)

return {allowed, math.floor(tokens), retry_ms}