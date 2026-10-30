-- Frees a rider from one order: deletes the busy marker only if it still points at that order.
-- KEYS[1] = {riders}:active:<riderId>, ARGV[1] = orderId
-- Returns 1 if the marker was removed. The rider rejoins the pool on their next ping.
if redis.call('get', KEYS[1]) == ARGV[1] then
    redis.call('del', KEYS[1])
    return 1
end
return 0
