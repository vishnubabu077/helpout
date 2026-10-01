-- Applies one helper availability event to the Redis projection, idempotently and out-of-order safe.
--
-- KEYS[1]  helper:{availabilityId}            hash with the candidate's details (has a TTL)
-- KEYS[2]  helper:{availabilityId}:version    last applied version (kept after removal as a tombstone)
-- KEYS[3+] helpers:geo:{categoryCode}         one GEO key per category of the event
--
-- ARGV[1] version      ARGV[2] mode UPSERT | REMOVE   ARGV[3] hash ttl seconds   ARGV[4] member (availabilityId)
-- ARGV[5] longitude    ARGV[6] latitude               ARGV[7] helperId            ARGV[8] status
-- ARGV[9] maxDistance  ARGV[10] categories (csv)      ARGV[11] version ttl secs   ARGV[12] repair 1|0
--
-- Returns 1 when applied, 0 when the event was stale or a duplicate.

local version = tonumber(ARGV[1])
local current = redis.call('GET', KEYS[2])

if current and tonumber(current) >= version then
    -- Stale or duplicate. Exception (rebuild only): same version but the hash is gone, so restore it.
    local repair = ARGV[12] == '1' and ARGV[2] == 'UPSERT'
        and tonumber(current) == version and redis.call('EXISTS', KEYS[1]) == 0
    if not repair then
        return 0
    end
end

if ARGV[2] == 'UPSERT' then
    for i = 3, #KEYS do
        redis.call('GEOADD', KEYS[i], ARGV[5], ARGV[6], ARGV[4])
    end
    redis.call('HSET', KEYS[1],
        'helperId', ARGV[7],
        'version', ARGV[1],
        'status', ARGV[8],
        'maxDistance', ARGV[9],
        'categories', ARGV[10])
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
else
    for i = 3, #KEYS do
        redis.call('ZREM', KEYS[i], ARGV[4])
    end
    redis.call('DEL', KEYS[1])
end

redis.call('SET', KEYS[2], ARGV[1], 'EX', tonumber(ARGV[11]))
return 1
