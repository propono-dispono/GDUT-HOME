package com.gdut.xiaojii.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 教务会话仓库（Redis）。
 *
 * 只负责一件事：把「教务下发的 Cookie」按用户存起来，并提供续期与清除。
 *
 * 【为什么单独抽一个类】
 * 写入方是 EduAuthServiceImpl（登录成功时），读取方是 EduDataServiceImpl（查课表时）。
 * 如果两边各写一份 key 前缀和过期时长，改了一处忘了另一处，就会出现
 * 「登录写 12 小时、查课表按 6 小时判断」这类对不上的问题，而且很难查。
 * 集中到这里之后，过期规则只有一份，两边只调方法。
 *
 * 【为什么不存明文密码】
 * 会话里只有 Cookie，没有账号密码。Cookie 是「临时门票」，泄漏了最多损失 24 小时，
 * 而且随时可以 logout 撤销；密码泄漏不可撤销。这是本类刻意只存 Cookie 的原因。
 *
 * @author 校集后端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EduSessionStore {

    /** 键前缀，后面接用户 ID，如 edu:session:12 */
    public static final String PREFIX = "edu:session:";

    /**
     * 每次成功访问后重置到的时长。
     * 【写法】12 小时，而不是原来固定的 6 小时。
     * 【为什么】原来从登录那一刻开始倒数 6 小时，用户上午用过、下午再进来就要重登。
     *   滑动之后，「一直在用」的人不会被打断，只有真正 12 小时没动过的人才需要重登。
     * 【代价】一份失效 Cookie 在 Redis 里多待几小时，影响仅限存储，不涉及权限。
     */
    public static final Duration SLIDING_TTL = Duration.ofHours(12);

    /**
     * 从首次登录起的绝对上限。
     * 【为什么必须有】只有滑动续期的话，用户每天打开一次小程序，Cookie 就能被无限续下去，
     *   等于永久有效。加一个 24 小时硬顶，保证「门票」最多活一天，泄漏窗口可控。
     */
    public static final Duration HARD_TTL = Duration.ofHours(24);

    private static final String FIELD_FIRST_AT = "firstAt";
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringRedisTemplate redis;

    /**
     * 登录成功后写入会话。
     *
     * 参数 userId：校集用户 ID，取自 JWT，不用前端传
     * 参数 cookies：教务 Cookie 头，形如 "JSESSIONID=A1B2; route=8f2e"
     * 参数 accountMasked：脱敏学号，只用于界面显示，如 3124****95
     */
    public void save(Long userId, String cookies, String accountMasked) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("cookies", cookies);
        session.put("accountMasked", accountMasked);
        session.put("loginAt", LocalDateTime.now().format(MINUTE));
        // firstAt 用毫秒时间戳，续期时要拿它算硬顶，不能只存格式化字符串
        session.put(FIELD_FIRST_AT, System.currentTimeMillis());
        try {
            redis.opsForValue().set(PREFIX + userId, JSON.writeValueAsString(session), SLIDING_TTL);
        } catch (Exception e) {
            log.error("[教务] 会话写入失败 userId={}", userId, e);
            throw new IllegalStateException("教务会话保存失败", e);
        }
    }

    /**
     * 读取会话 JSON。
     *
     * 返回：会话对象；没有会话或内容损坏时返回 null。
     * 说明：损坏时按「没有会话」处理而不是抛错，因为调用方本来就要处理「需要登录」这条路径。
     */
    public JsonNode get(Long userId) {
        String raw = redis.opsForValue().get(PREFIX + userId);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            log.warn("[教务] 会话 JSON 损坏，按未登录处理 userId={}", userId);
            return null;
        }
    }

    /**
     * 取 Cookie 头。
     *
     * 返回：形如 "JSESSIONID=A1B2; route=8f2e"；没有会话时返回 null。
     */
    public String cookies(Long userId) {
        JsonNode node = get(userId);
        if (node == null) {
            return null;
        }
        String cookies = node.path("cookies").asText("");
        return cookies.isBlank() ? null : cookies;
    }

    /**
     * 续期。每次成功访问教务之后调用。
     *
     * 【写法】重置为 12 小时，但如果「首次登录 + 24 小时」更早，就按更早的算。
     * 【为什么】滑动续期解决「一直在用却被踢」，硬顶解决「无限续期等于永久有效」。
     * 【代价】需要多读一次 Redis 拿 firstAt，好在同一次请求里本来就已经读过会话了。
     */
    public void touch(Long userId) {
        String key = PREFIX + userId;
        JsonNode node = get(userId);
        if (node == null) {
            return;
        }
        long firstAt = node.path(FIELD_FIRST_AT).asLong(0L);
        if (firstAt <= 0L) {
            // 老格式会话没有 firstAt（本次改动之前写入的），按现在算起点，让它自然过渡
            firstAt = System.currentTimeMillis();
        }
        long remainMs = firstAt + HARD_TTL.toMillis() - System.currentTimeMillis();
        if (remainMs <= 0L) {
            redis.delete(key);
            return;
        }
        Duration ttl = Duration.ofMillis(Math.min(SLIDING_TTL.toMillis(), remainMs));
        redis.expire(key, ttl);
    }

    /** 退出登录：直接删除，不做软删除 —— 会话没有「恢复」的需求 */
    public void clear(Long userId) {
        redis.delete(PREFIX + userId);
    }

    /** 剩余有效秒数；没有会话返回 0 */
    public long remainingSeconds(Long userId) {
        Long ttl = redis.getExpire(PREFIX + userId, TimeUnit.SECONDS);
        return ttl == null || ttl < 0 ? 0L : ttl;
    }

    /** 会话到期时间 yyyy-MM-dd HH:mm；没有会话返回空串 */
    public String expiresAt(Long userId) {
        long seconds = remainingSeconds(userId);
        if (seconds <= 0L) {
            return "";
        }
        return LocalDateTime.now().plusSeconds(seconds).format(MINUTE);
    }

    /** 脱敏学号；没有会话返回空串 */
    public String accountMasked(Long userId) {
        JsonNode node = get(userId);
        return node == null ? "" : node.path("accountMasked").asText("");
    }
}
