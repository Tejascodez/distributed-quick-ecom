-- Rider location ping. Only an online rider with no active order enters the idle pool, so a ping can't
-- put a busy or offline rider back while a claim or "offline" is in progress.
-- KEYS[1] = {riders}:idle, KEYS[2] = {riders}:online, KEYS[3] = {riders}:active:<riderId>, KEYS[4] = {riders}:last-seen
-- ARGV[1] = riderId, ARGV[2] = lng, ARGV[3] = lat, ARGV[4] = now epoch millis
-- Returns the rider's active order id, or nil when they are not busy.
redis.call('zadd', KEYS[4], ARGV[4], ARGV[1])
local active = redis.call('get', KEYS[3])
if active then
    return active
end
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then
    redis.call('geoadd', KEYS[1], ARGV[2], ARGV[3], ARGV[1])
end
return nil
