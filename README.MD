# 分布式限流器

Spring boot 启动器.

默认提供好了默认配置的 `FixWindowRateLimiter` 和 `SmoothBurstyRateLimiter` 实例.
`@Autowire` 注入后即可使用.

## `FixWindowRateLimiter` 固定窗口计数限流

窗口分割, 时间窗内限制最大流量.

## `SmoothBurstyRateLimiter` 平滑突发限流

参考 guava-rateLimiter 中 `SmoothBursty` 实现. 基于令牌桶算法, 支持流量一定程度突发.
