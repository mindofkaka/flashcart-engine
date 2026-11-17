-- KEYS[1] = flashcart:stock:<productId>, ARGV[1] = quantity to add
-- Return: new stock, or -1 if the key doesn't exist
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
return redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
