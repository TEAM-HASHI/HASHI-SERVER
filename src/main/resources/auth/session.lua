-- One member's sessions are serialized in one bounded hash. Redis TIME is authoritative.
local now = tonumber(redis.call('TIME')[1])
local entries = redis.call('HGETALL', KEYS[1])
local sessions = {}
for i = 1, #entries, 2 do
    local s = cjson.decode(entries[i + 1])
    if s.expires > now then sessions[entries[i]] = s
    else redis.call('HDEL', KEYS[1], entries[i]) end
end
local function removeGroup(sid)
    redis.call('HDEL', KEYS[1], sid)
    sessions[sid] = nil
    for id, s in pairs(sessions) do
        if s.parent == sid then
            redis.call('HDEL', KEYS[1], id)
            sessions[id] = nil
        end
    end
end
for id, s in pairs(sessions) do
    if s.parent ~= '' and sessions[s.parent] == nil then removeGroup(id) end
end
local op, sid, client, token, replacement, ttl = ARGV[1], ARGV[2], ARGV[3], ARGV[4], ARGV[5], tonumber(ARGV[6])
if op == 'revokeAll' then redis.call('DEL', KEYS[1]); return 1 end
if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
local function save(id, kind, hash, parent)
    if redis.call('HLEN', KEYS[1]) >= 128 and sessions[id] == nil then return -2 end
    local expires = now + ttl
    if parent ~= '' then expires = math.min(expires, sessions[parent].expires) end
    local s = {client=kind, token=hash, parent=parent, expires=expires}
    redis.call('HSET', KEYS[1], id, cjson.encode(s))
    redis.call('EXPIRE', KEYS[1], ttl)
    return 1
end
if op == 'save' then return save(sid, client, token, '') end
if op == 'migrate' then
    local old = redis.call('GET', KEYS[3])
    if old == false then return 0 end
    if old ~= ARGV[7] then redis.call('DEL', KEYS[3]); return -1 end
    local result = save(sid, 'web', replacement, '')
    if result == 1 then redis.call('DEL', KEYS[3]) end
    return result
end
local current = sessions[sid]
if current == nil or current.client ~= client then return 0 end
if op == 'rotate' or op == 'revoke' then
    if current.token ~= token then removeGroup(sid); return -1 end
    if op == 'revoke' then removeGroup(sid); return 1 end
    return save(sid, client, replacement, current.parent)
end
if op == 'issue' then
    if client ~= 'app' then return 0 end
    current.code = ARGV[7]
    redis.call('HSET', KEYS[1], sid, cjson.encode(current))
    redis.call('SET', KEYS[3], ARGV[8], 'EX', 60)
    return 1
end
if op == 'exchange' then
    if client ~= 'app' or current.code ~= ARGV[7] then return 0 end
    local encoded = redis.call('GET', KEYS[3])
    if encoded == false then return 0 end
    local code = cjson.decode(encoded)
    if code.challenge ~= ARGV[8] or code.parent ~= sid or code.userId ~= ARGV[9] then return 0 end
    local result = save(ARGV[10], 'web', replacement, sid)
    if result == 1 then
        current.code = nil
        redis.call('HSET', KEYS[1], sid, cjson.encode(current))
        redis.call('DEL', KEYS[3])
    end
    return result
end
return 0
