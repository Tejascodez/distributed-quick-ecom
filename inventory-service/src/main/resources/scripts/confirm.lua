-- Payment succeeded: the units are sold, so drop the hold without restoring stock.
-- KEYS[1] = res:{storeId}:orderId
-- Returns the confirmed [stockKey, qty, ...] pairs, or an empty list on redelivery.
local items = redis.call('hgetall', KEYS[1])
redis.call('del', KEYS[1])
return items
