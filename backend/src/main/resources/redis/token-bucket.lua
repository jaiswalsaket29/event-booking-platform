-- Token bucket, atomically in Redis (a script runs without interleaving with other commands).
-- KEYS[1]  bucket key (hash: tokens, ts)
-- ARGV[1]  capacity (max tokens, also the burst size)
-- ARGV[2]  refill period in ms (time to refill an empty bucket completely)
-- Returns {allowed (1/0), retry_after_ms}.
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local period_ms = tonumber(ARGV[2])

-- Use the Redis clock, not the app's, so every app instance sees the same time.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
    tokens = capacity
    ts = now
end

-- refill for the time that passed, continuously, up to capacity
local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + elapsed * capacity / period_ms)

local allowed = 0
local retry_after_ms = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    retry_after_ms = math.ceil((1 - tokens) * period_ms / capacity)
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
-- an untouched bucket is full again after one period, so it can simply disappear
redis.call('PEXPIRE', key, period_ms)
return {allowed, retry_after_ms}
