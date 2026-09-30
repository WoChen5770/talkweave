-- Fixed-width decimal headers, not Lua doubles: revision must remain exact above 2^53.
-- Frame = 19-digit revision + 10-digit coverage + newline + opaque JSON payload.
-- Java validates candidate size/header; existing values may be malformed or too large.
local candidate = ARGV[1]
local maxBytes = tonumber(ARGV[3])
local function validHeader(value)
    return string.len(value) >= 30 and string.sub(value, 30, 30) == '\n'
        and string.match(string.sub(value, 1, 29), '^%d+$') ~= nil
        and string.sub(value, 1, 19) <= '9223372036854775807'
end
if not validHeader(candidate) or string.len(candidate) > maxBytes then return -1 end
local length = redis.call('STRLEN', KEYS[1])
if length > 0 and length <= maxBytes then
    local old = redis.call('GET', KEYS[1])
    if old and validHeader(old) then
        local oldRevision = string.sub(old, 1, 19)
        local newRevision = string.sub(candidate, 1, 19)
        if oldRevision > newRevision then return 0 end
        if oldRevision == newRevision
            and string.sub(old, 20, 29) >= string.sub(candidate, 20, 29) then return 0 end
    end
end
redis.call('SET', KEYS[1], candidate, 'PX', ARGV[2])
return 1
