local operation = ARGV[1]
local now = tonumber(ARGV[2])
local member = ARGV[3]

local function prune(key, window)
    redis.call('ZREMRANGEBYSCORE', key, '-inf', '(' .. string.format('%.0f', now - window))
end

local function score_at(key, index)
    local entry = redis.call('ZRANGE', key, index, index, 'WITHSCORES')
    return entry[2] and tonumber(entry[2]) or nil
end

local function window_wait(key, window, limit)
    prune(key, window)
    if redis.call('ZCARD', key) < limit then
        return 0
    end
    return math.max(0, score_at(key, 0) + window - now)
end

local function record(key, window)
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window)
end

local function block_wait(key)
    local until_ms = redis.call('GET', key)
    return until_ms and math.max(0, tonumber(until_ms) - now) or 0
end

local function block(key, duration)
    redis.call('SET', key, string.format('%.0f', now + duration), 'PX', duration)
end

if operation == 'blocked' then
    local wait = 0
    for _, key in ipairs(KEYS) do
        wait = math.max(wait, block_wait(key))
    end
    return wait
end

if operation == 'reset' then
    return redis.call('DEL', unpack(KEYS))
end

local window = tonumber(ARGV[4])

if operation == 'login-failure' then
    local block_duration = tonumber(ARGV[5])
    for index = 1, #KEYS, 2 do
        local events = KEYS[index]
        local limit = tonumber(ARGV[6 + (index - 1) / 2])
        prune(events, window)
        record(events, window)
        if redis.call('ZCARD', events) >= limit then
            block(KEYS[index + 1], block_duration)
        end
    end
    return 0
end

if operation == 'otp-send' then
    local email_events, ip_events = KEYS[1], KEYS[2]
    local cooldown = tonumber(ARGV[5])
    local email_wait = window_wait(email_events, window, tonumber(ARGV[6]))
    local ip_wait = window_wait(ip_events, window, tonumber(ARGV[7]))
    local last_send = score_at(email_events, -1)
    local cooldown_wait = last_send and last_send + cooldown - now or 0
    local wait = math.max(email_wait, ip_wait, cooldown_wait)
    if wait > 0 then
        return wait
    end
    record(email_events, window)
    record(ip_events, window)
    return 0
end

if operation == 'otp-verify' then
    local wait = math.max(block_wait(KEYS[1]), window_wait(KEYS[2], window, tonumber(ARGV[5])))
    if wait > 0 then
        return wait
    end
    record(KEYS[2], window)
    return 0
end

if operation == 'otp-wrong' then
    local events, block_key = KEYS[1], KEYS[2]
    local wait = block_wait(block_key)
    if wait > 0 then
        return wait
    end
    prune(events, window)
    record(events, window)
    if redis.call('ZCARD', events) >= tonumber(ARGV[5]) then
        block(block_key, window)
        redis.call('DEL', events)
        return window
    end
    return 0
end

return redis.error_reply('Unsupported auth rate-limit operation')
