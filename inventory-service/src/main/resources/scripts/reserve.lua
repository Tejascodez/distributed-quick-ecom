-- Atomically reserves every line of an order, or nothing.
-- KEYS[1]   = res:{storeId}:orderId   (reservation hash: stock key -> qty)
-- KEYS[2..] = inv:{storeId}:productId (sellable counters), each at most once
-- ARGV[1]   = reservation TTL seconds (safety net; the sweeper releases first)
-- ARGV[2..] = positive integer quantity for the matching KEYS[i]
-- Returns 1 = reserved, 2 = already reserved (redelivery), -i = line i short on stock.
-- All keys share the {storeId} hash tag so this runs on one Redis Cluster slot.
local seen = {}
for i = 2, #KEYS do
    local qty = tonumber(ARGV[i])
    -- A repeated key would pass the stock check twice against the same counter and oversell.
    if seen[KEYS[i]] or not qty or qty <= 0 or qty % 1 ~= 0 then
        return redis.error_reply('invalid reservation line ' .. (i - 1))
    end
    seen[KEYS[i]] = true
end
if redis.call('exists', KEYS[1]) == 1 then
    return 2
end
for i = 2, #KEYS do
    local current = tonumber(redis.call('get', KEYS[i]) or '0')
    if current < tonumber(ARGV[i]) then
        return -(i - 1)
    end
end
for i = 2, #KEYS do
    redis.call('decrby', KEYS[i], ARGV[i])
    redis.call('hset', KEYS[1], KEYS[i], ARGV[i])
end
redis.call('expire', KEYS[1], ARGV[1])
return 1
