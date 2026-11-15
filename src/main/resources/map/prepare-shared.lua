-- Operator preflight while the public map feature is disabled. Concatenate shared.lua before this file.
-- Only the two known map metadata keys are allowed; no snapshot or authentication key is touched.
if #KEYS ~= 2 or KEYS[1] ~= 'hashi:restaurant:map:{sessions-shared-v3}:requests'
    or KEYS[2] ~= 'hashi:restaurant:map:{sessions-shared-v3}:admission' then return {-7, 0} end
local memory = redis.call('INFO', 'memory')
local used = tonumber(string.match(memory, 'used_memory:(%d+)'))
local max = tonumber(string.match(memory, 'maxmemory:(%d+)'))
local ceiling, headroom = tonumber(ARGV[1]), tonumber(ARGV[2])
if not used or not max or max <= 0 or not ceiling or not headroom or headroom <= 0
    or ceiling <= headroom or string.match(memory, 'maxmemory_policy:([^%s]+)') ~= 'volatile-lru'
    or used + headroom >= math.min(ceiling, max) then return {-5, 0} end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local state = sharedState(KEYS[1], KEYS[2], now)
if state == -6 then
    return {state, tonumber(redis.call('HGET', KEYS[1], '__readyAt')) - now}
end
return {state, 0}
