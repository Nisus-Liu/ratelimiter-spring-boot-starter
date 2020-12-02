package com.wtgroup.ratelimiter.core;

import com.wtgroup.ratelimiter.script.FixWindowScript;
import com.wtgroup.ratelimiter.script.RedisScript;
import com.wtgroup.ratelimiter.util.SleepUtil;
import org.springframework.util.Assert;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 固定窗口计数 限流器
 * <p>
 * Note:<br/>
 * 1> 时间窗内允许上限内突发. 窗口内没有做到更新颗粒度的匀速.<br/>
 * 2> 窗口临界处会存在 2倍 窗口上限的突发可能.
 *
 * @author dafei
 * @version 0.1
 * @date 2020/12/2 16:08
 */
public class FixWindowRateLimiter {

    private final JedisPool jedisPool;

    /**
     * 全局限制数, 在 acquire 没有指定 limit 时生效.
     * 默认 1000/S
     */
    private int globalLimit = 1000;
    /**
     * 类似 globalLimit , 默认 1S
     */
    private Duration globalWindow = Duration.ofSeconds(1);
    /**
     * 计数窗口依据的 redis key 前缀 (不用含分隔符)
     */
    private String keyPrefix = "FIX_WINDOW_RATE_LIMITER";
    /**
     * lua 脚本
     */
    private RedisScript script = new FixWindowScript();

    public FixWindowRateLimiter(JedisPool jedisPool) {
        this(jedisPool, null);
    }

    /**
     * @param jedisPool 操作 redis
     * @param keyPrefix 计数窗口依据的 redis key 前缀
     */
    public FixWindowRateLimiter(JedisPool jedisPool, String keyPrefix) {

        // if (jedisPool == null) {
        //     this.jedisPool = new JedisPool();
        // } else {
        //     this.jedisPool = jedisPool;
        // }

        Assert.notNull(jedisPool, "`jedisPool` is null");
        this.jedisPool = jedisPool;

        if (keyPrefix != null) {
            this.keyPrefix = keyPrefix;
        }
    }

    public FixWindowRateLimiter(JedisPool jedisPool, String keyPrefix, int globalLimit, Duration globalWindow) {
        this(jedisPool, keyPrefix);
        this.globalLimit = globalLimit;
        this.globalWindow = globalWindow;
    }

    public double acquire() {
        return this.acquire(this.globalLimit, this.globalWindow);
    }

    /**
     * 时间窗口精度到 ms .
     * 获取失败时, 休眠等待. 休眠到下一个时间窗口重试, 如此往复...
     * <pre>
     *               5
     *           +              +
     * +----------------+-------------------------------->
     *           +      ^       +
     *          1s      |      2s
     *                  |
     *                  |
     *                  +------>
     *                  +
     *                     now ~ next window
     * </pre>
     *
     * @param limit  上限
     * @param window 时间窗口长度
     * @return 拿到令牌等待时长, 秒, 不含和 redis 通信时间.
     */
    public double acquire(long limit, Duration window) {
        long waitedMs = 0L;
        while (!acquire0(limit, window)) {
            // 睡眠到下一个窗口
            long sleepTime = calcSleepTime(window);
            SleepUtil.sleepUninterruptibly(sleepTime, TimeUnit.MILLISECONDS);
            waitedMs += sleepTime;
        }

        return 1.0 * waitedMs / 1000;
    }

    public boolean tryAcquire() {
        return this.tryAcquire(Duration.ZERO);
    }

    /**
     * 非阻塞 acquire
     * <p>
     * 成功直接返回 true; 失败, 最多等待 timeout 时长. 含 redis 通信时间.<br/>
     * Note: 返回前最近一次的 redis 通信时长可能会引起一定误差.
     */
    public boolean tryAcquire(Duration timeout) {
        long end = System.currentTimeMillis() + timeout.toMillis();
        int limit = this.globalLimit;
        Duration window = this.globalWindow;
        while (!acquire0(limit, window)) {
            if (System.currentTimeMillis() >= end) {
                return false;
            }

            // 下个窗口开始时点在 end 前面, 则还有重试的希望, 否则, 肯定失败
            if (this.nextWindowBegin(window) >= end) {
                return false;
            }

            // 睡眠到下个窗口
            long sleepTime = calcSleepTime(window);
            SleepUtil.sleepUninterruptibly(sleepTime, TimeUnit.MILLISECONDS);
        }

        return true;
    }


    /**
     * 下一个窗口时点减去当前时间戳即是需要等待的时长
     *
     * @param window
     * @return ms
     */
    private long calcSleepTime(Duration window) {
        long nowMs = System.currentTimeMillis();
        return this.nextWindowBegin(window) - nowMs;
    }

    /**计算下一个窗口开始时间点
     * @param window
     * @return
     */
    private long nextWindowBegin(Duration window) {
        long nowMs = System.currentTimeMillis();
        long windMs = window.toMillis();
        return ((nowMs + windMs) / windMs) * windMs;
    }

    private boolean acquire0(long limit, Duration window) {
        Object eval = null;
        try (Jedis jedis = jedisPool.getResource()) {
            long windMs = window.toMillis();
            long windowSeq = System.currentTimeMillis() / windMs;
            String windowKey = this.keyPrefix + ":" + windowSeq;
            String windowLimit = String.valueOf(limit);
            // 加入ttl, 脚本中并不知晓时间窗口的大小
            // ttl
            long windSec = windMs / 1000;
            String ttl = String.valueOf(calcTtl(windSec));

            eval = jedis.eval(script.getScriptAsString(),
                    Arrays.asList(windowKey),
                    Arrays.asList(windowLimit, ttl));
        }

        if (eval == null) {
            throw new RuntimeException("eval FixWindowScript return null, rate limit fail");
        }

        return (Long) eval == 1;
    }

    /**
     * 为了保证 TTL 略长于计数窗口
     * <p>
     * 时间窗口在 10S 内, ttl 设为计数窗口的两倍;
     * > 10S, ttl 增加 10S;
     * 不足 1S 按 1S
     *
     * @param windSec 秒数
     * @return
     */
    private long calcTtl(long windSec) {
        if (windSec < 1) {
            return 1;
        }

        int threshold = 10;
        if (windSec <= threshold) {
            return windSec << 1;
        }

        return windSec + threshold;
    }


}
