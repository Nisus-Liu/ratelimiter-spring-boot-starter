package com.wtgroup.ratelimiter.core;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.JedisPool;

import java.time.Duration;

public class FixWindowRateLimiterTest {

    private JedisPool jedisPool;

    @Before
    public void before() {
        GenericObjectPoolConfig genericObjectPoolConfig = new GenericObjectPoolConfig();

        this.jedisPool = new JedisPool(genericObjectPoolConfig, "ws-docker");
    }

    @Test
    public void foo() {
        FixWindowRateLimiter rateLimiter = new FixWindowRateLimiter(jedisPool, null, 5, Duration.ofSeconds(1));

        while (true) {
            System.out.println("acquire wait " + rateLimiter.acquire() + " 秒");
        }
        /**
         * acquire wait 0.0 秒
         * acquire wait 0.0 秒
         * acquire wait 0.0 秒
         * acquire wait 0.0 秒
         * acquire wait 0.0 秒
         * acquire wait 0.813 秒
         * acquire wait 0.0 秒
         * acquire wait 0.0 秒
         * acquire wait 0.0 秒
         * acquire wait 0.0 秒
         * acquire wait 0.989 秒
         * */
    }

    @Test
    public void foo2() throws InterruptedException {
        FixWindowRateLimiter rateLimiter = new FixWindowRateLimiter(jedisPool, null, 5, Duration.ofSeconds(1));

        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        System.out.println(Thread.currentThread().getName() + " acquire wait " + rateLimiter.acquire() + " 秒");
                    }
                }
            });
            t.start();
        }

        Thread.currentThread().join();
    }

    @Test
    public void tryAcquire() {
        FixWindowRateLimiter rateLimiter = new FixWindowRateLimiter(jedisPool, null, 1000, Duration.ofSeconds(1));

        while (true) {
            System.out.println("tryAcquire : " + rateLimiter.tryAcquire() + " --" + System.currentTimeMillis()/1000);
        }
    }




}
