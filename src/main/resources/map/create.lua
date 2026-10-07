local memory = redis.call('INFO', 'memory')
local used = tonumber(string.match(memory, 'used_memory:(%d+)'))
local max = tonumber(string.match(memory, 'maxmemory:(%d+)'))
local policy = string.match(memory, 'maxmemory_policy:([^%s]+)')
local ceiling = tonumber(ARGV[1])
local headroom = tonumber(ARGV[2])
if not used or not max or policy ~= 'noeviction' then return -2 end
if max > 0 then ceiling = math.min(ceiling, max) end
if used + headroom >= ceiling then return -2 end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local absolute = tonumber(ARGV[4])
local idle = tonumber(ARGV[5])
local bytes = #ARGV[3]
if bytes > tonumber(ARGV[6]) then return -1 end
if not absolute or absolute <= now or absolute > now + tonumber(ARGV[10]) then return -2 end
if redis.call('HLEN', KEYS[2]) > 4096 then return -2 end
local entries = redis.call('HGETALL', KEYS[2])
local total, count = 0, 0
for index = 1, #entries, 2 do
    local expiry, reserved = string.match(entries[index + 1], '(%d+):(%d+)')
    if not expiry or not reserved then return -2 end
    if tonumber(expiry) <= now then
        redis.call('HDEL', KEYS[2], entries[index])
    else
        total = total + tonumber(reserved)
        count = count + 1
    end
end
-- Reserve twice the serialized bytes plus per-key/ledger overhead; INFO is a second, global guard.
local reservation = bytes * 2 + 1024
if total + reservation > tonumber(ARGV[7]) or count >= tonumber(ARGV[8]) then return -1 end
if redis.call('EXISTS', KEYS[1]) == 1 then return -2 end
local expiry = math.min(absolute, now + idle)
redis.call('HSET', KEYS[2], ARGV[9], expiry .. ':' .. reservation)
redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[10]) + 1000)
-- Reserve first. If SET fails, the reservation expires conservatively instead of leaving unaccounted data.
redis.call('SET', KEYS[1], ARGV[3], 'PXAT', expiry)
return expiry
