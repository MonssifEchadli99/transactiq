local stored_fingerprint = redis.call('HGET', KEYS[1], 'fingerprint')
if stored_fingerprint then
    if stored_fingerprint ~= ARGV[1] then
        return {'CONFLICT'}
    end
    return {
        'DUPLICATE',
        redis.call('HGET', KEYS[1], 'transaction_count'),
        redis.call('HGET', KEYS[1], 'amounts'),
        redis.call('HGET', KEYS[1], 'countries'),
        redis.call('HGET', KEYS[1], 'observed_at')
    }
end

redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[3])
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', ARGV[4])
redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', ARGV[5])

redis.call('ZADD', KEYS[2], ARGV[2], ARGV[6])
redis.call('ZADD', KEYS[3], ARGV[2], ARGV[6] .. '|' .. ARGV[7])
redis.call('ZADD', KEYS[4], ARGV[2], ARGV[6] .. '|' .. ARGV[8])

local transaction_count = redis.call('ZCARD', KEYS[2])
local amount_members = redis.call('ZRANGE', KEYS[3], 0, -1)
local country_members = redis.call('ZRANGE', KEYS[4], 0, -1)

local function values_after_separator(members)
    local values = {}
    for index, member in ipairs(members) do
        local separator = string.find(member, '|', 1, true)
        values[index] = string.sub(member, separator + 1)
    end
    return values
end

local amounts = table.concat(values_after_separator(amount_members), ',')
local countries = table.concat(values_after_separator(country_members), ',')

redis.call('HSET', KEYS[1],
        'fingerprint', ARGV[1],
        'transaction_count', tostring(transaction_count),
        'amounts', amounts,
        'countries', countries,
        'observed_at', ARGV[2])

redis.call('PEXPIRE', KEYS[1], ARGV[12])
redis.call('PEXPIRE', KEYS[2], ARGV[9])
redis.call('PEXPIRE', KEYS[3], ARGV[10])
redis.call('PEXPIRE', KEYS[4], ARGV[11])

return {'RECORDED', tostring(transaction_count), amounts, countries, ARGV[2]}
