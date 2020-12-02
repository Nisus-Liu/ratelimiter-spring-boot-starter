package com.wtgroup.ratelimiter.script;

/**预留令牌
 * @author dafei
 * @version 0.1
 * @date 2020/12/3 0:55
 */
public class SmoothBurstyReserveScript implements RedisScript{
    public static final String SCRIPT =
            "local rlInfo = redis.call('HMGET', KEYS[1], 'nextFreeTicketMicros', 'stableIntervalMicros', 'maxPermits', 'storedPermits')\n" +
            "local nextFreeTicketMicros = tonumber(rlInfo[1])\n" +
            "local stableIntervalMicros = tonumber(rlInfo[2])\n" +
            "local maxPermits = tonumber(rlInfo[3])\n" +
            "local storedPermits = tonumber(rlInfo[4])\n" +
            "local requiredPermits = tonumber(ARGV[1])\n" +
            "if requiredPermits == nil then\n" +
            "    requiredPermits = 1\n" +
            "end\n" +
            "redis.replicate_commands()\n" +
            "local time = redis.call('TIME')\n" +
            "local nowMicros = time[1] * 1000000 + time[2]\n" +
            "if nowMicros > nextFreeTicketMicros then\n" +
            "    local newPermits = (nowMicros - nextFreeTicketMicros) / stableIntervalMicros\n" +
            "    storedPermits = math.min(maxPermits, storedPermits + newPermits)\n" +
            "    nextFreeTicketMicros = nowMicros\n" +
            "end\n" +
            "local oldNextFreeTicketMicros = nextFreeTicketMicros\n" +
            "local storedPermitsToSpend = math.min(requiredPermits, storedPermits);\n" +
            "local freshPermits = requiredPermits - storedPermitsToSpend\n" +
            "local waitMicros = freshPermits * stableIntervalMicros\n" +
            "nextFreeTicketMicros = nextFreeTicketMicros + waitMicros\n" +
            "storedPermits = storedPermits - storedPermitsToSpend\n" +
            "redis.call('HMSET', KEYS[1], 'storedPermits', storedPermits, 'nextFreeTicketMicros', nextFreeTicketMicros)\n" +
            "local towait = math.max(oldNextFreeTicketMicros - nowMicros, 0)\n" +
            "return towait";

    @Override
    public String getScriptAsString() {
        return SCRIPT;
    }
}
