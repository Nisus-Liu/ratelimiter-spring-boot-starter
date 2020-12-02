package com.wtgroup.ratelimiter.script;

/**
 * 初始化 redis 数据
 *
 * @author dafei
 * @version 0.1
 * @date 2020/12/3 0:52
 */
public class SmoothBurstyInitScript implements RedisScript {
    public static final String SCRIPT =
            "local permitsPerSecond = tonumber(ARGV[1])\n" +
            "local stableIntervalMicros = 1000000 / permitsPerSecond\n" +
            "local maxPermits = 1.0 * permitsPerSecond\n" +
            "local storedPermits = permitsPerSecond\n" +
            "redis.call('HMSET', KEYS[1], 'stableIntervalMicros', stableIntervalMicros, 'storedPermits', storedPermits, 'maxPermits', maxPermits, 'nextFreeTicketMicros', 0)\n" +
            "return 1";

    @Override
    public String getScriptAsString() {
        return SCRIPT;
    }
}
