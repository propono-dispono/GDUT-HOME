package com.gdut.xiaojii.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解（自研，非第三方）。
 *
 * 【它解决什么问题】
 * 发帖、私信、举报这类写接口，一个用户短时间连点几十次，会灌进几十条待审内容、
 * 几十条重复私信。靠前端防抖挡不住（抓包直接打接口），必须在服务端按"人 + 接口"计数。
 *
 * 【怎么用】
 * 挂在 Controller 方法上，由 RateLimitAspect 在方法执行前拦一次：
 *   @RateLimit(api = "post:create", limit = 5, windowSeconds = 3600)
 *   public Result<PostDetailVO> create(...)
 * 含义：同一用户 1 小时内最多发 5 条帖子。
 *
 * 【为什么用注解而不是在 Service 里手工调用】
 * 限流是"横切关注点"——它跟业务逻辑无关，却要在几十个接口上重复。
 * 写成注解后：① 一眼能看出这个接口有没有被限流；② 阈值改在接口旁边，不用翻到 Service；
 * ③ 将来换算法（换成令牌桶）只改切面，所有接口自动生效。
 * 【代价】多一层 AOP 代理，方法调用多几十微秒；且注解只能加在 public 方法上
 * （AOP 走代理，private/同类内部调用不生效 —— 这是 Spring AOP 的通用限制）。
 *
 * @author 校集后端
 */
// 【写法】三个元注解缺一不可
// 【为什么】
//   @Target(METHOD)      —— 只允许挂在方法上。挂类上语义不明（是整个类共用一个额度，还是每个方法各算？）
//   @Retention(RUNTIME)  —— 必须保留到运行时。切面是靠反射在运行期读这个注解的，
//                           若用默认的 CLASS，编译进 .class 后运行期读不到，限流会静默失效
//   @Documented          —— 让它出现在接口文档里，方便他人知道这个接口有限流
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 接口标识，拼进 Redis key，用来区分"哪个接口的额度"。
     *
     * 参数：形如 "post:create" / "chat:send" / "report:submit"
     * 说明：不能用类名+方法名自动生成 —— 方法改名会让老 key 变成孤儿，
     *   且同一业务逻辑换个方法名就重置了额度（等于绕过限流）。显式写死更可控。
     */
    String api();

    /**
     * 窗口内允许的最大次数。
     *
     * 参数：正整数。默认 5（与"发帖 5 次/小时"对齐）
     * 模拟数据：limit=5，用户 5 分钟内点了 5 次发帖 → 第 6 次被拒；
     *   第 1 次的时间戳滑出窗口后，第 6 次又能通过。
     */
    int limit() default 5;

    /**
     * 窗口长度（秒）。
     *
     * 参数：正整数。默认 3600（1 小时）
     * 模拟数据：windowSeconds=3600，第 1 次请求在 10:00:00，
     *   到 11:00:00 之后这个时间戳被清出窗口，额度恢复 1 次。
     */
    int windowSeconds() default 3600;

    /**
     * 自定义提示语，留空则自动拼"请 X 秒/分钟后再试"。
     *
     * 参数：空串表示用默认文案
     * 模拟数据：message="" → "操作太频繁，请 42 分钟后再试"
     *   message="今天发帖次数已用完，明天再来" → 原样返回
     */
    String message() default "";
}
