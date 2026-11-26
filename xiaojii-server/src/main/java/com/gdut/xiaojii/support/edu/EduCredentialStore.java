package com.gdut.xiaojii.support.edu;

import com.gdut.xiaojii.entity.EduCredential;
import com.gdut.xiaojii.mapper.EduCredentialMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 教务账号凭证仓库（加密存学号 + 密码，用于 Cookie 失效后自动重登）。
 *
 * 【它解决什么问题】
 * 教务只给我们两种凭证：Cookie（十几小时就废）和密码（长期有效）。
 * 要让用户"30 天不用重输密码"，就必须把密码存下来 —— 这是唯一的路。
 *
 * 【存什么】
 * AES-GCM 加密后的密文（学号 + 换行 + 密码），存进 edu_credential 表。
 * 表里同时存 nonce（GCM 的随机数）和 keyVersion（密钥版本，支持轮换）。
 *
 * 【为什么用 AES-GCM 而不是 AES-CBC】
 * GCM 自带完整性校验：密文被改动一个字节就解不开，Cipher.doFinal 直接抛 AEADBadTagException。
 * CBC 不会报错，只会解出乱码 —— 那就变成"用户密码莫名其妙登不上"这种极难排查的问题。
 *
 * 【密钥从哪来】
 * 环境变量 EDU_CRED_KEY，32 字节的 Base64 字符串（配置项 edu.cred-key）。
 * 为什么放环境变量而不是配置文件：库里的密文和密钥必须分开存，
 * 否则拖库时等于把钥匙和保险箱一起给了人。
 *
 * 【⚠️ 密钥没配时会怎样】
 * available() 返回 false，所有方法安全降级（不存、不读、不报错）。
 * 这样部署时即使忘了配密钥，也只是"回到原来没有记忆功能"的状态，不会让教务功能崩掉。
 *
 * 【两层有效期】
 * SLIDING_DAYS = 30 天：每次成功自动重登后往后推（天天用就一直有效）
 * HARD_DAYS = 90 天：从首次写入算，最多 90 天必须重绑（防止密码长期躺库）
 *
 * @author 校集后端
 */
// @Slf4j：加解密失败、密钥缺失等异常要留日志（但绝不打明文密码）
@Slf4j
// @Component：注册成 Bean，EduAuthServiceImpl / EduDataServiceImpl 注入使用
@Component
// @RequiredArgsConstructor：为 final 字段生成构造器（只注入 Mapper）
@RequiredArgsConstructor
public class EduCredentialStore {

    /** 滑动有效期：每次成功重登后往后推 */
    // 【为什么是 30 天】用户明确要求"30 天内不用重登"。
    //   做成滑动而不是绝对，是为了让"天天用的人"感受不到系统的存在
    public static final int SLIDING_DAYS = 30;

    /** 硬顶：从首次写入算，最多活这么久 */
    // 【为什么必须有】只有滑动的话，天天打开的用户密码会被无限续期 = 永久有效，
    //   泄漏窗口无限长。加一个 90 天硬顶，强制重新确认密码
    public static final int HARD_DAYS = 90;

    /** 当前密钥版本；将来换密钥时自增，旧版密文用旧密钥解，实现平滑轮换 */
    private static final int KEY_VERSION = 1;

    /** GCM 的 nonce 长度，12 字节是标准推荐值 */
    private static final int NONCE_BYTES = 12;
    /** GCM 认证标签长度（位），128 位是默认值 */
    private static final int TAG_BITS = 128;
    /** 账号与密码之间的分隔符（学号不含换行，所以换行是安全的分隔符） */
    private static final String SEP = "\n";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64E = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    // 凭据表的数据访问：按 userId 存取、删除
    private final EduCredentialMapper credentialMapper;

    /** 对称密钥的原始字节；密钥未配置或格式错误时保持 null */
    // 【为什么用 volatile】Spring 注入是单线程的，但读发生在业务线程；
    //   volatile 保证其他线程能看到注入后的值（懒初始化的可见性问题）
    private volatile byte[] keyBytes;

    /**
     * 注入密钥。
     *
     * 为什么用 setter 注入而不是构造器注入：
     * 构造器注入会让"密钥缺失"变成启动失败 —— 而这个功能缺失不该阻止整个应用启动。
     * 用 setter 注入，密钥缺失只是让 available() 返回 false，服务照常跑。
     */
    @Value("${edu.cred-key:}")
    public void setCredentialKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.keyBytes = null;
            log.info("[教务凭据] 未配置 edu.cred-key（环境变量 EDU_CRED_KEY），自动重登功能关闭（只保留 Cookie 会话）");
            return;
        }
        try {
            byte[] raw = B64D.decode(base64Key.trim());
            if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
                this.keyBytes = null;
                log.error("[教务凭据] edu.cred-key 长度非法（{} 字节），必须是 16/24/32 字节的 Base64", raw.length);
                return;
            }
            this.keyBytes = raw;
            log.info("[教务凭据] 密钥已加载，长度 {} 字节，自动重登功能开启", raw.length);
        } catch (Exception e) {
            this.keyBytes = null;
            log.error("[教务凭据] edu.cred-key 不是合法的 Base64，自动重登功能关闭", e);
        }
    }

    /** 功能是否可用（密钥配置正确时为 true） */
    // 【调用方必须先问它】所有写入/读取前都要判断，否则会在缺密钥时抛异常
    public boolean available() {
        return keyBytes != null;
    }

    /**
     * 保存凭证（覆盖写）。
     *
     * 参数 userId：校集用户 ID
     * 参数 account：学号
     * 参数 password：教务密码（明文传入，本方法内部立刻加密，不落任何日志）
     *
     * 说明：密钥未配置时直接返回 false，不抛异常 —— 让"没配密钥"表现为"功能未启用"，
     * 而不是让登录流程失败。
     */
    public boolean save(Long userId, String account, String password) {
        if (!available()) {
            return false;
        }
        if (userId == null || account == null || account.isBlank() || password == null || password.isEmpty()) {
            log.warn("[教务凭据] 保存参数不完整，跳过 userId={}", userId);
            return false;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            // 明文格式：学号 + \n + 密码。一次加密两样，重登时一次解密就够
            byte[] plain = (account + SEP + password).getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] cipherBytes = cipher.doFinal(plain);

            LocalDateTime now = LocalDateTime.now();
            EduCredential entity = new EduCredential();
            entity.setUserId(userId);
            entity.setPayloadCipher(B64E.encodeToString(cipherBytes));
            entity.setNonce(B64E.encodeToString(nonce));
            entity.setAccountMasked(mask(account));
            entity.setKeyVersion(KEY_VERSION);
            entity.setExpiresAt(now.plusDays(SLIDING_DAYS));
            entity.setUpdatedAt(now);

            // 【写法】先 delete 再 insert，而不是 updateById
            // 【为什么】updateById 在主键不存在时不会插入（影响行数 0，不报错），
            //   首次保存会"看起来成功但库里没有"。先删再插无论如何都能写进去。
            // 【代价】两次 SQL；但这是登录时才执行的低频操作，可以接受
            credentialMapper.deleteById(userId);
            entity.setCreatedAt(now);
            credentialMapper.insert(entity);
            log.info("[教务凭据] 已保存 userId={} account={} 有效期至 {}",
                    userId, entity.getAccountMasked(), entity.getExpiresAt());
            return true;
        } catch (Exception e) {
            // 【为什么不抛异常】保存失败不该让"登录成功"变成"登录失败"。
            //   降级效果：本次登录正常，只是下次 Cookie 过期时要手动重登
            log.error("[教务凭据] 保存失败 userId={}", userId, e);
            return false;
        }
    }

    /**
     * 读取凭证（解密）。
     *
     * 返回：学号 + 密码；没有记录、已过期、密钥不匹配、密文损坏时返回 null。
     * 说明：任何异常都按"没有凭证"处理，让调用方走"请重新登录"路径，而不是抛错让接口 500。
     */
    public Credential load(Long userId) {
        if (!available() || userId == null) {
            return null;
        }
        EduCredential entity;
        try {
            entity = credentialMapper.selectById(userId);
        } catch (Exception e) {
            log.error("[教务凭据] 查询失败 userId={}", userId, e);
            return null;
        }
        if (entity == null || entity.getPayloadCipher() == null || entity.getNonce() == null) {
            return null;
        }
        // 过期就别解了，直接清掉 —— 留着也是废物，还占风险
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.info("[教务凭据] 已过期，清除 userId={} expiresAt={}", userId, entity.getExpiresAt());
            clear(userId);
            return null;
        }
        if (entity.getKeyVersion() != null && entity.getKeyVersion() != KEY_VERSION) {
            // 密钥轮换后旧密文解不开：清掉，让用户重绑
            log.warn("[教务凭据] 密钥版本不匹配（{} != {}），清除 userId={}",
                    entity.getKeyVersion(), KEY_VERSION, userId);
            clear(userId);
            return null;
        }
        try {
            byte[] nonce = B64D.decode(entity.getNonce());
            byte[] cipherBytes = B64D.decode(entity.getPayloadCipher());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            String plain = new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
            int idx = plain.indexOf(SEP);
            if (idx <= 0) {
                log.warn("[教务凭据] 明文格式异常，清除 userId={}", userId);
                clear(userId);
                return null;
            }
            return new Credential(plain.substring(0, idx), plain.substring(idx + SEP.length()));
        } catch (Exception e) {
            // 密钥换了、密文被改、存的时候截断了 —— 都归到这里
            // 【重要】这类失败必须清掉记录，否则每次请求都会白试一次解密
            log.warn("[教务凭据] 解密失败，清除记录 userId={} err={}", userId, e.getClass().getSimpleName());
            clear(userId);
            return null;
        }
    }

    /**
     * 续期（成功自动重登后调用）。
     *
     * 规则：有效期推到「现在 + 30 天」，但不超过「首次写入 + 90 天」。
     * 返回：续期后的到期时间；已到硬顶或没有记录时返回 null（调用方应视为"凭证即将作废"）。
     */
    public LocalDateTime touch(Long userId) {
        if (!available() || userId == null) {
            return null;
        }
        EduCredential entity;
        try {
            entity = credentialMapper.selectById(userId);
        } catch (Exception e) {
            log.error("[教务凭据] 续期查询失败 userId={}", userId, e);
            return null;
        }
        if (entity == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime hardLimit = (entity.getCreatedAt() == null ? now : entity.getCreatedAt()).plusDays(HARD_DAYS);
        if (!now.isBefore(hardLimit)) {
            // 到硬顶：主动删除，强制用户重新确认密码
            log.info("[教务凭据] 已达 {} 天硬顶，清除 userId={}", HARD_DAYS, userId);
            clear(userId);
            return null;
        }
        LocalDateTime next = now.plusDays(SLIDING_DAYS);
        // 取"滑动到期"和"硬顶"里更早的那个
        if (next.isAfter(hardLimit)) {
            next = hardLimit;
        }
        try {
            EduCredential update = new EduCredential();
            update.setUserId(userId);
            update.setExpiresAt(next);
            update.setUpdatedAt(now);
            credentialMapper.updateById(update);
            return next;
        } catch (Exception e) {
            log.error("[教务凭据] 续期失败 userId={}", userId, e);
            return null;
        }
    }

    /** 删除凭证（退出登录、密码错误、用户解绑时调用） */
    public void clear(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            credentialMapper.deleteById(userId);
        } catch (Exception e) {
            // 删除失败只记日志：残留一条密文不会造成功能错误，最多占一行存储
            log.warn("[教务凭据] 删除失败 userId={}", userId, e);
        }
    }

    /** 是否存在有效凭证（不解密，只判存在且未过期）—— 给 status 接口显示"已记住"用 */
    public boolean exists(Long userId) {
        if (!available() || userId == null) {
            return false;
        }
        try {
            EduCredential entity = credentialMapper.selectById(userId);
            return entity != null
                    && entity.getPayloadCipher() != null
                    && entity.getExpiresAt() != null
                    && entity.getExpiresAt().isAfter(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[教务凭据] 判断存在性失败 userId={}", userId, e);
            return false;
        }
    }

    /** 剩余有效天数；没有凭证返回 0 —— 给前端提示"还有 X 天需要重新绑定" */
    public long remainingDays(Long userId) {
        if (!available() || userId == null) {
            return 0L;
        }
        try {
            EduCredential entity = credentialMapper.selectById(userId);
            if (entity == null || entity.getExpiresAt() == null) {
                return 0L;
            }
            long seconds = java.time.Duration.between(LocalDateTime.now(), entity.getExpiresAt()).getSeconds();
            return seconds <= 0 ? 0L : seconds / 86400L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 学号脱敏：保留前 4 后 2，如 3124004295 → 3124****95 */
    private String mask(String account) {
        if (account == null || account.length() <= 6) {
            return account == null ? "" : account;
        }
        return account.substring(0, 4) + "****" + account.substring(account.length() - 2);
    }

    /**
     * 解密后的凭证。
     *
     * 【为什么用不可变类】密码一旦读出就该只读，避免在调用链里被意外修改或打印。
     * 【为什么没有 toString】刻意不覆盖 —— 默认的 Object.toString 只输出类名和哈希，
     *   不会把 password 带出来。这样即使有人误把对象打进日志，也不会泄露密码。
     */
    public static final class Credential {
        private final String account;
        private final String password;

        public Credential(String account, String password) {
            this.account = account;
            this.password = password;
        }

        public String getAccount() {
            return account;
        }

        public String getPassword() {
            return password;
        }
    }
}
