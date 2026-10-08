local memory = redis.call('INFO', 'memory')
local used = tonumber(string.match(memory, 'used_memory:(%d+)'))
local max = tonumber(string.match(memory, 'maxmemory:(%d+)'))
local policy = string.match(memory, 'maxmemory_policy:([^%s]+)')
local ceiling = tonumber(ARGV[1])
local headroom = tonumber(ARGV[2])
if not used or not max or policy ~= 'noeviction' then return -5 end
if max > 0 then ceiling = math.min(ceiling, max) end
if used + headroom >= ceiling then return -5 end
local time = redis.call('TIME')
local now = tonumber(time[1])
local window = math.floor(now / 60)
local previous = tonumber(redis.call('HGET', KEYS[1], 'window'))
if previous ~= window then
    redis.call('DEL', KEYS[1])
    redis.call('HSET', KEYS[1], 'window', window, 'total', 0)
    redis.call('EXPIRE', KEYS[1], 120)
end
local total = tonumber(redis.call('HGET', KEYS[1], 'total'))
local caller = redis.call('HGET', KEYS[1], ARGV[3])
if not caller and redis.call('HLEN', KEYS[1]) - 2 >= tonumber(ARGV[4]) then return -1 end
local calls, queries = 0, 0
if caller then calls, queries = string.match(caller, '(%d+):(%d+)') end
calls, queries = tonumber(calls), tonumber(queries)
local isNew = tonumber(ARGV[8])
if total >= tonumber(ARGV[5]) then return -4 end
if calls >= tonumber(ARGV[6]) or (isNew == 1 and queries >= tonumber(ARGV[7])) then return -3 end
redis.call('HSET', KEYS[1], 'total', total + 1, ARGV[3], (calls + 1) .. ':' .. (queries + isNew))
return now * 1000 + math.floor(tonumber(time[2]) / 1000)
