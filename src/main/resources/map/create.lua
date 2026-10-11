local memory = redis.call('INFO', 'memory')
local used = tonumber(string.match(memory, 'used_memory:(%d+)'))
local max = tonumber(string.match(memory, 'maxmemory:(%d+)'))
local policy = string.match(memory, 'maxmemory_policy:([^%s]+)')
local ceiling = tonumber(ARGV[1])
local headroom = tonumber(ARGV[2])
local shared = ARGV[11] == '1'
if not used or not max or (shared and (policy ~= 'volatile-lru' or max <= 0))
    or (not shared and policy ~= 'noeviction') then return -5 end
if max > 0 then ceiling = math.min(ceiling, max) end
if used + headroom >= ceiling then return -5 end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if shared then
    local state = sharedState(KEYS[3], KEYS[2], now)
    if state ~= 0 then return state end
end
local absolute = tonumber(ARGV[4])
local idle = tonumber(ARGV[5])
local bytes = #ARGV[3]
if bytes > tonumber(ARGV[6]) then return -1 end
if not absolute or absolute <= now or absolute > now + tonumber(ARGV[10]) then return -2 end
if redis.call('HLEN', KEYS[2]) > (shared and 4097 or 4096) then return shared and -7 or -2 end
local entries = redis.call('HGETALL', KEYS[2])
local total, count = 0, 0
for index = 1, #entries, 2 do
    if not shared or entries[index] ~= '__readyAt' then
        local expiry, reserved = string.match(entries[index + 1], '^(%d+):(%d+)$')
        if not expiry or not reserved or tonumber(reserved) < 1024 then return shared and -7 or -2 end
        if tonumber(expiry) <= now then
            redis.call('HDEL', KEYS[2], entries[index])
        else
            total = total + tonumber(reserved)
            count = count + 1
        end
    end
end
-- Reserve twice the serialized bytes plus per-key/ledger overhead; INFO is a second, global guard.
local reservation = bytes * 2 + 1024
-- Account for the impending allocation too, rather than only the memory before SET.
if used + reservation + headroom >= ceiling then return -5 end
if total + reservation > tonumber(ARGV[7]) then return -3 end
if count >= tonumber(ARGV[8]) then return -4 end
if redis.call('EXISTS', KEYS[1]) == 1 then return -2 end
local expiry = math.min(absolute, now + idle)
local ledgerTtl = redis.call('PTTL', KEYS[2])
redis.call('HSET', KEYS[2], ARGV[9], expiry .. ':' .. reservation)
-- A shorter lifetime on a rolling instance must not expire another instance's reservations.
-- Preserve an existing persistent ledger; a missing ledger receives the new lifetime.
if not shared and ledgerTtl ~= -1 then
    redis.call('PEXPIRE', KEYS[2], math.max(ledgerTtl, tonumber(ARGV[10]) + 1000))
end
-- Reserve first. If SET fails, the reservation expires conservatively instead of leaving unaccounted data.
redis.call('SET', KEYS[1], ARGV[3], 'PXAT', expiry)
return expiry
