package com.gdut.xiaojii.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置项的载体：把 application.yml 里的 xiaojii.jwt.* 搬进一个 Java 对象。
 *
 * 【这个类到底干什么】
 * 配置写在 yml 里是"文本"，Java 代码要用就得先读出来、转成对象。
 * 这个类就是那个"对象模具"：Spring 启动时扫到它，会把 yml 里同名的配置项
 * 逐个塞进对应字段；之后别的类通过构造器注入 JwtProperties，就能拿到值。
 *
 * 【为什么不用 @Value 一个个注入】
 * 用 @Value("${xiaojii.jwt.secret}") 也能拿到值，但有三点不如集中配置：
 * ① 配置项散落在各处，改个名字要满项目找；
 * ② 没法写默认值（yml 漏配就直接启动失败）；
 * ③ 类型转换要自己处理，写错位置要到运行时才炸。
 * 集中到一个类里，一次绑定、处处可用，漏配还能用 Java 里写好的默认值兜住。
 *
 * 【yml 的 kebab-case 怎么对上 Java 的 camelCase】
 * yml 写 expire-seconds，Java 字段叫 expireSeconds —— 靠 Spring Boot 的
 * "宽松绑定"（relaxed binding）：横杠、下划线、大小写都会被忽略后再匹配。
 * 所以不需要手写转换，名字对得上就自动注入。
 *
 * @author 校集后端
 */
@Data                       // 【注解作用】Lombok 生成 getter/setter。⛔ 不能删：Spring 绑定配置靠 setter 赋值
@Component                  // 【注解作用】注册成 Spring Bean，别的类才能注入它
@ConfigurationProperties(prefix = "xiaojii.jwt")   // 【注解作用】核心注解：声明"去 yml 读 xiaojii.jwt 开头那一段"
public class JwtProperties {

    /**
     * 签名密钥（HS256 用的对称密钥，签发和验签是同一把）。
     *
     * 【为什么至少要 32 字节】
     * HS256 的安全强度取决于密钥长度，密钥太短等于锁做得太薄。
     * jjwt 库会直接拒绝短密钥，抛 WeakKeyException —— 而 JwtUtil 启动时就会校验这一点。
     *
     * 【为什么生产必须从环境变量注入】
     * 写在 yml 里会跟着代码进 Git 仓库，等于把密钥公开。
     * 所以 yml 里写的是 ${JWT_SECRET:某默认值}，线上用环境变量覆盖，
     * 代码仓库里永远只有占位符。
     */
    private String secret;

    /**
     * 小程序用户令牌的有效期，单位：秒。默认 604800 秒 = 7 天。
     *
     * 【为什么用秒而不是天】
     * JWT 标准和 jjwt 库都按秒/毫秒算，直接用秒能少一层换算，避免"天转秒"乘错。
     * 604800 = 7 × 24 × 60 × 60，这种数字算错了很难一眼看出来，所以注释里写清。
     *
     * 【为什么定 7 天】
     * 太短：用户要频繁重新授权（小程序每次打开都静默登录，体验差）；
     * 太长：令牌一旦泄露，攻击窗口也变长。
     * 7 天是体验与安全之间的折中。
     */
    private long expireSeconds = 604800L;

    /**
     * 管理端令牌的有效期，单位：秒。默认 43200 秒 = 12 小时。
     *
     * 【为什么比小程序端短这么多】
     * 后台账号权限远大于普通用户（能审核、能封禁、能看全站数据）。
     * 管理员一天工作 8 小时，12 小时足够覆盖一个工作日；
     * 万一账号被盗，最长 12 小时就自然失效，不会像 7 天那样留下长期后门。
     */
    private long adminExpireSeconds = 43200L;

    /**
     * 令牌放在哪个请求头里，默认 Authorization。
     *
     * 【为什么做成可配置】
     * 如果以后对接第三方网关或旧系统，对方可能要求换成 X-Token 这类自定义头，
     * 改配置即可，不用改代码。
     */
    private String header = "Authorization";

    /**
     * 令牌前缀，默认 "Bearer "（Bearer 是 OAuth 2.0 规定的固定写法）。
     *
     * 【注意末尾那个空格，别删】
     * 最终请求头长这样：Authorization: Bearer eyJhbGciOi...
     * 解析时要按 "Bearer "（含空格）切掉前缀才能取出纯令牌。
     * 少了空格，切完会剩 "yJ..." 这种半截字符串，验签必然失败，
     * 而且报错信息只会说"令牌无效"，很难联想到是少了个空格。
     */
    private String prefix = "Bearer ";
}
