package com.gdut.xiaojii.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置。
 *
 * <p>默认的 {@code RedisTemplate} 使用 JDK 序列化，在 redis-cli 里会看到一堆乱码，
 * 排查问题时非常痛苦。这里改成：
 * <ul>
 *   <li>key → String（可读）</li>
 *   <li>value → JSON（可读，含 @class 类型信息，便于反序列化回原类型）</li>
 * </ul>
 *
 * <p>Redis 在本项目中的用途：
 * <ol>
 *   <li>登录态白名单（可主动踢人）—— 用 {@code StringRedisTemplate}，不要用本类的 template</li>
 *   <li>接口限流（发布 5 次/小时、私信 60 条/分钟）—— 用 {@code StringRedisTemplate}（ZSET）</li>
 *   <li>用户状态缓存（封禁校验）—— {@code StringRedisTemplate}</li>
 * </ol>
 *
 * <p><b>⚠️ 安全说明：</b>原实现用 {@code activateDefaultTyping(LaissezFaireSubTypeValidator)}
 * 放行一切类型，等价于"允许从 Redis 反序列化任意类"，是经典的反序列化利用面。
 * 这里改用 {@link GenericJackson2JsonRedisSerializer} 的内置构造（它自带白名单式类型处理），
 * 并且只在确实需要存对象的场景使用本 template。
 *
 * @author 校集后端
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        ObjectMapper mapper = new ObjectMapper();
        // 支持 LocalDateTime 等 Java 8 时间类型
        mapper.registerModule(new JavaTimeModule());
        // 白名单式类型信息：只允许本项目的类与 JDK 常用容器，
        // 而不是 LaissezFaire（放行一切）——后者可被利用做反序列化攻击
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.gdut.xiaojii.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.time.")
                        .allowIfSubType("java.lang.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL);

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(mapper);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
