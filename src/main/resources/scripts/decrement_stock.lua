--KEYS[1] = flashcart:stock:<productId>
--ARGV[1] = quantity yang diminta

local stock = redis.call('GET', KEYS[1])

if stock == false then
    return -1
end

local qty = tonumber(ARGV[1])

if tonumber(stock) < qty then
    return 0
end 

redis.call('DECRBY', KEYS[1], qty)
return 1