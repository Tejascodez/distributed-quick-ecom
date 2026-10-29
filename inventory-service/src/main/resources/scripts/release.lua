-- Compensation: returns reserved units to the sellable pool.
-- KEYS[1] = res:{storeId}:orderId
-- Returns the released [stockKey, qty, ...] pairs, or an empty list if nothing was held.
local items = redis.call('hgetall', KEYS[1])
for i = 1, #items, 2 do
    redis.call('incrby', items[i], items[i + 1])
end
redis.call('del', KEYS[1])
return items
