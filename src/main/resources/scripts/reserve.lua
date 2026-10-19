-- KEYS[1] = stock key, KEYS[2] = per-user purchased key, KEYS[3] = idempotency key
-- ARGV[1] = qty, ARGV[2] = per-user limit, ARGV[3] = idempotency TTL (seconds)
-- returns remaining stock (>= 0) on success
--   -1 = sold out, -2 = user limit exceeded, -3 = duplicate request (key already accepted)
if redis.call('EXISTS', KEYS[3]) == 1 then return -3 end

local stock  = tonumber(redis.call('GET', KEYS[1]) or '0')
local bought = tonumber(redis.call('GET', KEYS[2]) or '0')
local qty    = tonumber(ARGV[1])

if stock < qty then return -1 end
if bought + qty > tonumber(ARGV[2]) then return -2 end

redis.call('DECRBY', KEYS[1], qty)
redis.call('INCRBY', KEYS[2], qty)
redis.call('SET', KEYS[3], 'ACCEPTED', 'EX', tonumber(ARGV[3]))
return stock - qty
