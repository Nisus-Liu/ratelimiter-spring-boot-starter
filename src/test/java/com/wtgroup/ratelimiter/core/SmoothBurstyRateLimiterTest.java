package com.wtgroup.ratelimiter.core;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.TimeUnit;

public class SmoothBurstyRateLimiterTest {
    private JedisPool jedisPool;

    private SmoothBurstyRateLimiter rateLimiter;

    @Before
    public void before() {
        GenericObjectPoolConfig genericObjectPoolConfig = new GenericObjectPoolConfig();

        this.jedisPool = new JedisPool(genericObjectPoolConfig, "ws-docker");
    }

    @Test
    public void initTest() {
        rateLimiter = new SmoothBurstyRateLimiter(jedisPool, null, 5);

    }

    @Test
    public void acquireTest() {
        rateLimiter = new SmoothBurstyRateLimiter(jedisPool, null, 5);

        rateLimiter.acquire(3);
    }

    @Test
    public void SmoothBursty() {
        rateLimiter = new SmoothBurstyRateLimiter(jedisPool, "SBRL_1", 5);

        while (true) {
            System.out.println("get 5 tokens: " + rateLimiter.acquire(5) + "s"); // 等待 0
            System.out.println("get 1 tokens: " + rateLimiter.acquire(1) + "s"); // 等待 1s --> 由于 redis 通信带来误差, 这一步没有等待, 误差刚好及时补充了需要令牌
            System.out.println("get 1 tokens: " + rateLimiter.acquire(1) + "s"); // 等待 200ms
            System.out.println("get 1 tokens: " + rateLimiter.acquire(1) + "s"); // 等待 200ms
            System.out.println("end");
        }

        /**
         * get 5 tokens: 0.0s
         * get 1 tokens: 0.0s
         * get 1 tokens: 0.196113s
         * get 1 tokens: 0.19736s
         * end
         * get 5 tokens: 0.199558s
         * get 1 tokens: 0.998003s
         * get 1 tokens: 0.199159s
         * get 1 tokens: 0.199445s
         * end
         */
    }

    @Test
    public void SmoothBursty_2() {
        rateLimiter = new SmoothBurstyRateLimiter(jedisPool, "SBRL_2", 5);

        System.out.println("get 1 tokens: " + rateLimiter.acquire(10) + "s");
        while (true) {
            System.out.println("get 1 tokens: " + rateLimiter.acquire(10) + "s");
        }
    }

    @Test
    public void destroyTest() {
        rateLimiter = new SmoothBurstyRateLimiter(jedisPool, "SBRL_2", 5);
        rateLimiter.destroy();
    }

    @Test
    public void tryAcquireTest() throws InterruptedException {
        rateLimiter = new SmoothBurstyRateLimiter(jedisPool, "SBRL_2", 5);

        for (int i = 0; i < 1; i++) {
            System.out.println("get 5 tokens: " + rateLimiter.tryAcquire(5));
            TimeUnit.SECONDS.sleep(1); // 等一秒后, 预期下次能拿到一个令牌
            System.out.println("get 1 tokens: " + rateLimiter.tryAcquire(1));
            System.out.println("get 1 tokens: " + rateLimiter.tryAcquire(1));
            System.out.println("get 1 tokens: " + rateLimiter.tryAcquire(1));
            System.out.println("end");
        }
        /**
         * get 5 tokens: true
         * get 1 tokens: true
         * get 1 tokens: false
         * get 1 tokens: false
         * end
         * */
    }


    @After
    public void after() {
        rateLimiter.destroy();
    }

}
