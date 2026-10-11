local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if ARGV[4] == '1' then
    local memory = redis.call('INFO', 'memory')
    local max = tonumber(string.match(memory, 'maxmemory:(%d+)'))
    if string.match(memory, 'maxmemory_policy:([^%s]+)') ~= 'volatile-lru'
        or not max or max <= 0 then return -5 end
    local state = sharedState(KEYS[3], KEYS[2], now)
    if state ~= 0 then return state end
end
local ttl = redis.call('PTTL', KEYS[1])
local entry = redis.call('HGET', KEYS[2], ARGV[1])
if ttl <= 0 or not entry then return -1 end
local expiry, reserved = string.match(entry, '^(%d+):(%d+)$')
if not expiry or not reserved or tonumber(reserved) < 1024 or tonumber(expiry) <= now then return -1 end
local absolute = tonumber(ARGV[3])
if absolute <= now then return -1 end
local nextExpiry = math.min(absolute, math.max(now + ttl, now + tonumber(ARGV[2])))
redis.call('PEXPIREAT', KEYS[1], nextExpiry)
redis.call('HSET', KEYS[2], ARGV[1], nextExpiry .. ':' .. reserved)
return nextExpiry
