package com.gdut.xiaojii.common.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * 管理员密码哈希（PBKDF2-HMAC-SHA256）。
 *
 * <p><b>为什么不用 Spring Security 的 BCrypt：</b>本项目只有 1-2 个管理员，
 * 为了一个密码哈希引入整个 spring-security 依赖（含一整套过滤器链、默认登录页）
 * 不划算。PBKDF2 是 JDK 自带实现（{@code SecretKeyFactory}），无需第三方库，
 * 安全性足够（迭代次数够高即可）。
 *
 * <p>存储格式：{@code pbkdf2$迭代次数$盐(Base64)$哈希(Base64)}，
 * 把参数一起存进去，将来调高迭代次数时旧密码仍可校验。
 *
 * <p>生成种子数据：运行 {@code java -cp ... PasswordUtil 你的密码} 或直接用
 * {@code mvn exec:java}，把输出粘进 {@code sql/init_data.sql}。
 *
 * @author 校集后端
 */
public final class PasswordUtil {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final String PREFIX = "pbkdf2";

    private PasswordUtil() {
    }

    /** 生成哈希串，入库用 */
    public static String hash(String rawPassword) {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * 校验密码。
     * 任何格式异常都返回 false（不要抛异常，避免把"数据库里存了脏数据"暴露给登录接口）。
     */
    public static boolean verify(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations);
            return constantTimeEquals(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
    }

    /** 定长比较，避免通过响应时间侧信道猜测密码 */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    /**
     * 命令行生成哈希：{@code java com.gdut.xiaojii.common.util.PasswordUtil.init("admin123")}
     * 或直接用 IDE 运行 main。
     */
    public static void main(String[] args) {
        String raw = args.length > 0 ? args[0] : "admin123";
        System.out.println("明文: " + raw);
        System.out.println("哈希: " + hash(raw));
    }
}
