local memory = redis.call('INFO', 'memory')
local used = tonumber(string.match(memory, 'used_memory:(%d+)'))
local max = tonumber(string.match(memory, 'maxmemory:(%d+)'))
local policy = string.match(memory, 'maxmemory_policy:([^%s]+)')
local ceiling = tonumber(ARGV[1])
local headroom = tonumber(ARGV[2])
local shared = ARGV[9] == '1'
if not used or not max or (shared and (policy ~= 'volatile-lru' or max <= 0))
    or (not shared and policy ~= 'noeviction') then return -5 end
if max > 0 then ceiling = math.min(ceiling, max) end
if used + headroom >= ceiling then return -5 end
local time = redis.call('TIME')
local now = tonumber(time[1])
local nowMillis = now * 1000 + math.floor(tonumber(time[2]) / 1000)
if shared then
    local state = sharedState(KEYS[1], KEYS[2], nowMillis)
    if state ~= 0 then return state end
end
local window = math.floor(now / 60)
local previous = tonumber(redis.call('HGET', KEYS[1], 'window'))
if shared and (not previous or previous > window or redis.call('HLEN', KEYS[1]) > 4099) then return -7 end
if previous ~= window then
    local readyAt = redis.call('HGET', KEYS[1], '__readyAt')
    redis.call('DEL', KEYS[1])
    redis.call('HSET', KEYS[1], 'window', window, 'total', 0)
    if shared then redis.call('HSET', KEYS[1], '__readyAt', readyAt)
    else redis.call('EXPIRE', KEYS[1], 120) end
end
local total = tonumber(redis.call('HGET', KEYS[1], 'total'))
local caller = redis.call('HGET', KEYS[1], ARGV[3])
local metadataFields = shared and 3 or 2
if not total or total < 0 then return -7 end
if not caller and redis.call('HLEN', KEYS[1]) - metadataFields >= tonumber(ARGV[4]) then return -1 end
local calls, queries = 0, 0
if caller then calls, queries = string.match(caller, '^(%d+):(%d+)$') end
calls, queries = tonumber(calls), tonumber(queries)
if not calls or not queries or queries > calls or calls > total then return -7 end
local isNew = tonumber(ARGV[8])
if total >= tonumber(ARGV[5]) then return -4 end
if calls >= tonumber(ARGV[6]) or (isNew == 1 and queries >= tonumber(ARGV[7])) then return -3 end
redis.call('HSET', KEYS[1], 'total', total + 1, ARGV[3], (calls + 1) .. ':' .. (queries + isNew))
return nowMillis
