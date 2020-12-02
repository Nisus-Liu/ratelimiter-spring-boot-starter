package com.wtgroup.ratelimiter.core;

import com.wtgroup.ratelimiter.consts.Consts;
import com.wtgroup.ratelimiter.script.RedisScript;
import com.wtgroup.ratelimiter.script.SmoothBurstyInitScript;
import com.wtgroup.ratelimiter.script.SmoothBurstyReserveScript;
import com.wtgroup.ratelimiter.util.SleepUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 平滑突发限流器
 * <p>
 * 仿照 guava 框架中的 {@link com.google.common.util.concurrent.SmoothRateLimiter.SmoothBursty} .
 *
 * <p>
 * Note:<br/>
 * 1> 时间计算, 尽量使其不依赖客户端应用. 而是集中依赖 redis . 此时, 注意 redis 需要 ^3.2 版本, 支持 redis.replicate_commands() .
 *
 * </p>
 *
 * @author dafei
 * @version 0.1
 * @date 2020/12/3 0:51
 */
@Slf4j
public class SmoothBurstyRateLimiter {

    private static RedisScript smoothBurstyInitScript = new SmoothBurstyInitScript();
    private static RedisScript smoothBurstyReserveScript = new SmoothBurstyReserveScript();
    /**
     * 暂写死过期 360 天
     */
    private static int RATE_LIMITER_INFO_KEY_TTL = 360 * 24 * 3600;

    private final JedisPool jedisPool;
    /**
     * 每秒可用的令牌数, 决定了限流器的速率.
     */
    private final double permitsPerSecond;
    /**
     * 限流器 id , 即 redis 中 key
     */
    private String id = "SMOOTH_BURSTY_RATE_LIMITER";

    public SmoothBurstyRateLimiter(JedisPool jedisPool, String id, double permitsPerSecond) {
        Assert.notNull(jedisPool, "`jedisPool` is null");
        this.jedisPool = jedisPool;
        if (!StringUtils.isEmpty(id)) {
            this.id = id;
        }
        Assert.isTrue(permitsPerSecond > 0, "`permitsPerSecond` must greater then 0");
        this.permitsPerSecond = permitsPerSecond;

        init();
    }


    /**
     * 阻塞式获取令牌
     * <p>
     * {@link SmoothBurstyRateLimiter#acquire(int)}
     * <p>
     * This method is equivalent to {@code acquire(1)}.
     *
     * @return
     */
    public double acquire() {
        return acquire(1);
    }

    /**
     * 阻塞式获取令牌
     *
     * @return time spent sleeping to enforce rate, in seconds; 0.0 if not rate-limited
     */
    public double acquire(int permits) {
        long microsToWait = reserve(permits);
        // 睡眠
        SleepUtil.sleepUninterruptibly(microsToWait, MICROSECONDS);

        return 1.0 * microsToWait / SECONDS.toMicros(1L);
    }

    private long reserve(int permits) {
        Assert.isTrue(permits > 0, "Requested permits must be positive");

        try (Jedis jedis = jedisPool.getResource()) {

            // 返回需要睡眠的微妙数
            Object res = jedis.eval(smoothBurstyReserveScript.getScriptAsString(),
                    Collections.singletonList(String.valueOf(this.id)),
                    Collections.singletonList(String.valueOf(permits)));

            return (Long) res;

        }
    }

    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    public boolean tryAcquire(int permits) {
        return tryAcquire(permits, Duration.ZERO);
    }

    /**
     * 非阻塞式获取
     * <p>
     * 在指定超时时间内返回结果.
     *
     * @param permits
     * @param timeout
     * @return
     */
    public boolean tryAcquire(int permits, Duration timeout) {
        Assert.isTrue(permits > 0, "Requested permits must be positive");
        long timeoutMicros = max(timeout.toNanos()/1000, 0);

        long microsToWait;
        long nowMicros = System.currentTimeMillis() * 1000; // 精度损失, 会导致 ns 获取不到, 故用毫秒
        if (!canAcquire(nowMicros, timeoutMicros)) {
            return false;
        } else {
            microsToWait = reserve(permits);
        }
        // 预计需要睡眠的时长超过了 timeout , 提前返回 (这是和 guava 不同的地方)
        if (microsToWait > timeoutMicros) {
            return false;
        }

        SleepUtil.sleepUninterruptibly(microsToWait, MICROSECONDS);

        return true;
    }

    /**
     * 当前时点+超时时长 落在 最近一次可释放令牌时点 之前, 那么, 必然无法成功 acquire .
     *
     * @param nowMicros
     * @param timeoutMicros
     * @return
     */
    private boolean canAcquire(long nowMicros, long timeoutMicros) {
        return queryEarliestAvailable() - timeoutMicros <= nowMicros;
    }

    /**
     * 查询 nextFreeTicketMicros
     *
     * @return
     */
    long queryEarliestAvailable() {
        try (Jedis jedis = jedisPool.getResource()) {
            String res = jedis.hget(this.id, Consts.nextFreeTicketMicros);
            return Long.parseLong(res);
        }
    }

    /**
     * 初始化 redis 限流器元数据, 可重复调用, 没有太大副作用.
     * <p>
     * 不管旧的是否存在, 在 new 时, 都再次初始化.<br/>
     * 考虑场景: 上次透支了很多令牌. 本次系统重启了, 如果不进行初始化, 那么旧的 <code>nextFreeTicketMicros</code> 会在将来很久.
     * 导致, 即使系统重启了, 依然阻塞请求很久很久. 这是不合理的, 而是应该, 重置.
     */
    private void init() {
        try (Jedis jedis = jedisPool.getResource()) {
            // 检查 key 是否已存在. 存在继续检查是否符合格式要求:
            // 1) 字段符合规范; 2) 值无异常.

            // if (jedis.exists(this.id)) {
            //     // throw new RuntimeException("Exists redis key : " + this.id);
            //     Map<String, String> rlinfo = jedis.hgetAll(this.id);
            //     checkRateLimiterInfo(rlinfo);
            //     // 设置较长的 ttl , 更新 ttl
            //     jedis.expire(this.id, RATE_LIMITER_INFO_KEY_TTL);
            //     log.info("SmoothBurstyRateLimiter's info is exits, and valid, return.");
            //     return;
            // }

            Object res = jedis.eval(smoothBurstyInitScript.getScriptAsString(),
                    Collections.singletonList(String.valueOf(this.id)),
                    Collections.singletonList(String.valueOf(this.permitsPerSecond)));

            if (res == null || (Long) res != 1) {
                throw new RuntimeException("Init SmoothBurstyRateLimiter fail. id: " + this.id);
            }

            // 设置较长的 ttl
            jedis.expire(this.id, RATE_LIMITER_INFO_KEY_TTL);

            log.info("SmoothBurstyRateLimiter init finished.");
        }
    }


    /**
     * 销毁: 删除 redis key
     */
    public void destroy() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(this.id);
        }
    }


    private void checkRateLimiterInfo(Map<String, String> rlinfo) {
        Assert.isTrue(rlinfo.containsKey(Consts.maxPermits), "SmoothBurstyRateLimiter redis info hasn't field of `" + Consts.maxPermits + "`");
        Assert.isTrue(rlinfo.containsKey(Consts.storedPermits), "SmoothBurstyRateLimiter redis info hasn't field of `" + Consts.storedPermits + "`");
        Assert.isTrue(rlinfo.containsKey(Consts.stableIntervalMicros), "SmoothBurstyRateLimiter redis info hasn't field of `" + Consts.stableIntervalMicros + "`");
        Assert.isTrue(rlinfo.containsKey(Consts.nextFreeTicketMicros), "SmoothBurstyRateLimiter redis info hasn't field of `" + Consts.nextFreeTicketMicros + "`");

        // stableIntervalMicros > 0
        Assert.isTrue(getDouble(rlinfo, Consts.stableIntervalMicros) > 0, "SmoothBurstyRateLimiter redis info's `stableIntervalMicros` must > 0");
        Assert.isTrue(getDouble(rlinfo, Consts.storedPermits) >= 0, "SmoothBurstyRateLimiter redis info's `storedPermits` must > 0");
        Assert.isTrue(getDouble(rlinfo, Consts.maxPermits) > 0, "SmoothBurstyRateLimiter redis info's `maxPermits` must > 0");
    }

    private double getDouble(Map<String, String> map, String key) {
        String res = map.get(key);
        if (res == null) {
            return 0;
        }
        return Double.parseDouble(res);
    }


}
