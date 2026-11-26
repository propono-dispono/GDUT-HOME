package com.gdut.xiaojii.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置（分布式锁用）。
 *
 * 【一句话】这里只是告诉 Redisson"你的 Redis 在哪、叫什么密码"，然后交出一个 RedissonClient
 * 给别人拿锁用。
 *
 * 【为什么不用 redisson-spring-boot-starter】
 * starter 会顺带接管 Spring Data Redis 的自动配置（换上它自己的连接工厂），
 * 而本项目已有 StringRedisTemplate 在用：限流切面（RateLimitAspect 的 ZSET）、
 * 消息队列（RedisStreamMessageQueue 的 XADD/XREADGROUP）、token 白名单。
 * 一旦底层连接工厂被换，序列化方式和连接池行为都可能变，
 * 出问题时的表现是"限流突然失效"或"队列读不到消息"—— 排查成本远高于省下的几行配置。
 * 【代价】两套客户端各维护一个连接池（Redisson 默认 8 条 + Spring Data 一套），
 * 多占几条连接。本项目量级完全无感。
 *
 * 【为什么是单机模式 useSingleServer】
 * 现在只有一个 Redis 实例。将来如果上 Redis 集群（主从 + 哨兵 / Cluster），
 * 这里改成 useSentinelServers 或 useClusterServers，业务代码一行不动 ——
 * 这正是"配置和业务分离"的好处。
 *
 * @author 校集后端
 */
@Configuration
public class RedissonConfig {

    // 复用 spring.data.redis 的配置：不要把地址再抄一遍
    // 【为什么】抄一遍就会出现"改了一处忘了另一处"，表现为锁连到旧地址、报连接超时
    @Value("${spring.data.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    // 本地开发 redis 没有密码，生产有；空字符串要转成 null，否则 Redisson 会拿空串去 AUTH
    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    /**
     * 这一段是"建一个连到 Redis 的客户端，它同时管着锁、限流、布隆过滤器等一堆东西"，
     * 本项目只用它的锁能力。
     *
     * 【destroyMethod = "shutdown"】容器关闭时自动 shutdown，否则 Netty 的线程池不会退出，
     * 本地反复重启会看到"端口被占用"或 JVM 退不干净。
     *
     * 【连接池为什么只要 8 条】锁操作是"发一条命令、等一个响应"，每次几十微秒；
     * 8 条连接在 2C4G 上足够支撑每秒上千次加解锁。开大了反而占 Redis 的 maxclients。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                // redisson 的地址必须带协议前缀，写 "127.0.0.1:6379" 会解析失败
                .setAddress("redis://" + host + ":" + port)
                .setPassword(password == null || password.isBlank() ? null : password)
                .setDatabase(database)
                // 最小空闲连接：保持 2 条热的，避免每次加锁都建连接（建连接要 3 次握手）
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8)
                // 命令超时：Redis 卡死时不要无限等，3 秒抛异常比线程堆着强
                .setTimeout(3000)
                .setConnectTimeout(3000)
                // 失败重试：网络抖动时自动重连，重试 3 次仍失败才抛
                .setRetryAttempts(3)
                .setRetryInterval(1500);
        return Redisson.create(config);
    }
}
