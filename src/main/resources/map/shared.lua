-- Shared volatile-lru can evict snapshots, but must not evict their reservation/rate metadata.
-- If metadata disappears, stop all writers/touches for the hard 30-minute lifetime. This lets
-- unaccounted snapshots expire without scanning, deleting, or interpreting any authentication key.
local function sharedState(requests, ledger, now)
    local requestsType = redis.call('TYPE', requests).ok
    local ledgerType = redis.call('TYPE', ledger).ok
    if (requestsType ~= 'none' and requestsType ~= 'hash')
        or (ledgerType ~= 'none' and ledgerType ~= 'hash') then return -7 end
    if requestsType == 'none' or ledgerType == 'none' then
        local readyAt = now + 1800000
        redis.call('DEL', requests, ledger)
        redis.call('HSET', requests, '__readyAt', readyAt, 'window', -1, 'total', 0)
        redis.call('HSET', ledger, '__readyAt', readyAt)
        return -6
    end
    -- A TTL or malformed marker must never silently reset accounting.
    if redis.call('PTTL', requests) ~= -1 or redis.call('PTTL', ledger) ~= -1 then return -7 end
    local requestsReady = tonumber(redis.call('HGET', requests, '__readyAt'))
    local ledgerReady = tonumber(redis.call('HGET', ledger, '__readyAt'))
    if not requestsReady or not ledgerReady or requestsReady < 0 or requestsReady ~= ledgerReady then return -7 end
    if now < requestsReady then return -6 end
    return 0
end
