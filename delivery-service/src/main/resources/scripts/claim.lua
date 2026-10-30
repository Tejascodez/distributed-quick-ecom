-- Claims an idle rider for an order: removes them from the pool and marks them busy, atomically.
-- KEYS[1] = {riders}:idle, KEYS[2] = {riders}:active:<riderId>
-- ARGV[1] = riderId, ARGV[2] = orderId, ARGV[3] = marker TTL seconds until the assignment is committed
-- Returns 1 = claimed, 0 = rider was not idle (another dispatcher won, or they went busy/offline).
if redis.call('exists', KEYS[2]) == 1 then
    redis.call('zrem', KEYS[1], ARGV[1])
    return 0
end
if redis.call('zrem', KEYS[1], ARGV[1]) == 0 then
    return 0
end
-- The TTL frees the rider if this dispatcher dies before committing; the commit makes the marker permanent.
redis.call('set', KEYS[2], ARGV[2], 'EX', ARGV[3])
return 1
