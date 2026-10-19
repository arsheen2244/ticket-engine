-- KEYS[1] = stock key, KEYS[2] = per-user purchased key
-- ARGV[1] = qty, ARGV[2] = per-user limit
-- returns remaining stock, -1 = sold out, -2 = user limit exceeded
local stock  = tonumber(redis.call('GET', KEYS[1]) or '0')
local bought = tonumber(redis.call('GET', KEYS[2]) or '0')
local qty    = tonumber(ARGV[1])

if stock < qty then return -1 end
if bought + qty > tonumber(ARGV[2]) then return -2 end

redis.call('DECRBY', KEYS[1], qty)
redis.call('INCRBY', KEYS[2], qty)
return stock - qty
