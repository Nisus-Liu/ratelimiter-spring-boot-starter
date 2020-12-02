package com.wtgroup.ratelimiter.script;

/**
 * 固定窗口计数脚本
 *
 * @author dafei
 * @version 0.1
 * @date 2020/12/2 15:57
 */
public class FixWindowScript implements RedisScript{

    private static final String SCRIPT =
            "local key = KEYS[1]\n" +
            "local limit = tonumber(ARGV[1])\n" +
            "local ttl = tonumber(ARGV[2])\n" +
            "local current = tonumber(redis.call('get', key) or \"0\")\n" +
            "if current + 1 > limit then\n" +
            "    return 0\n" +
            "else\n" +
            "    redis.call(\"INCRBY\", key, \"1\")\n" +
            "    redis.call(\"EXPIRE\", key, ttl)\n" +
            "    return 1\n" +
            "end";


    @Override
    public String getScriptAsString() {
        return SCRIPT;
    }
}
