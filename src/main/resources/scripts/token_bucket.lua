-- KEYS[1] : The Redis Key (e.g., rate_limit:user:123:/api/orders)
-- ARGV[1] : Capacity (Max tokens)
-- ARGV[2] : Refill Rate (Tokens per second)
-- ARGV[3] : Current Timestamp (Millis)
-- ARGV[4] : Requested Tokens (Usually 1)
-- ARGV[5] : TTL in Seconds (To clean up Redis memory)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5])

-- 1. Fetch current state
local raw_state = redis.call("HMGET", key, "tokens", "last_refill")
local tokens = tonumber(raw_state[1])
local last_refill = tonumber(raw_state[2])

-- 2. Initialize if it's the first time this user hits this endpoint
if tokens == nil then
    tokens = capacity
    last_refill = now
else
    -- 3. Calculate Refill (The "Lazy" Refill logic we perfected in Java)
    local elapsed = math.max(0, now - last_refill)
    local refill = math.floor((elapsed * refill_rate) / 1000)
    tokens = math.min(capacity, tokens + refill)
end

-- 4. Decision Logic
local allowed = 0
local retry_after = 0

if tokens >= requested then
    allowed = 1
    tokens = tokens - requested
    last_refill = now -- Only update the refill anchor on success
else
    -- Calculate how long until the user has enough tokens for the 'requested' amount
    -- Formula: (needed_tokens * 1000) / refill_rate
    retry_after = math.ceil((requested - tokens) * 1000 / refill_rate)
end

-- 5. Persist and Set Expiry (Improvement: Self-cleaning Redis)
redis.call("HMSET", key, "tokens", tokens, "last_refill", last_refill)
redis.call("EXPIRE", key, ttl)

-- Return format: {allowed, remaining_tokens, retry_after_ms}
return { allowed, tokens, retry_after }