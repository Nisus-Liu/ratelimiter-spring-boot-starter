package com.wtgroup.ratelimiter.config;

import com.wtgroup.ratelimiter.core.FixWindowRateLimiter;
import com.wtgroup.ratelimiter.core.SmoothBurstyRateLimiter;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;

import javax.annotation.Resource;

/**
 * @author Nisus Liu
 * @version 0.0.1
 * @email liuhejun108@163.com
 * @date 2019/4/30 17:13
 */
@Configuration
@EnableConfigurationProperties({RedisProperties.class, RateLimiterProperties.class})
// @Import(OnOffEndpoint.class)
public class RateLimiterAutoConfiguration {

    @Resource
    private RedisProperties redisProperties;

    @Bean
    @ConditionalOnMissingBean(JedisPool.class)
    public JedisPool jedisPool() {
        // 配置默认的 jedisPool
        //this(new GenericObjectPoolConfig(), host, port, 2000, (String)null, 0, (String)null);
        //JedisPool(GenericObjectPoolConfig poolConfig, String host, int port, int timeout, String password)
        GenericObjectPoolConfig genericObjectPoolConfig = new GenericObjectPoolConfig();

        JedisPool jedisPool = new JedisPool(genericObjectPoolConfig,
                redisProperties.getHost(),
                redisProperties.getPort(),
                Math.toIntExact(redisProperties.getTimeout().getSeconds()),
                redisProperties.getPassword());
        return jedisPool;
    }

    @Bean
    @ConditionalOnMissingBean
    public FixWindowRateLimiter fixWindowRateLimiter(JedisPool jedisPool) {

        FixWindowRateLimiter rateLimiter = new FixWindowRateLimiter(jedisPool);

        return rateLimiter;
    }

    @Bean
    @ConditionalOnMissingBean
    public SmoothBurstyRateLimiter smoothBurstyRateLimiter(JedisPool jedisPool) {

        SmoothBurstyRateLimiter rateLimiter = new SmoothBurstyRateLimiter(jedisPool, null, 1000);

        return rateLimiter;
    }



}
