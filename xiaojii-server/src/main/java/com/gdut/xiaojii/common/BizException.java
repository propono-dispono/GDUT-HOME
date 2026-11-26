package com.gdut.xiaojii.common;

import lombok.Getter;

/**
 * 业务异常。
 *
 * Service 层遇到"可预期的失败"（如 token 过期、用户被封禁、帖子不存在）时抛出本异常，
 * 由 GlobalExceptionHandler 统一转换成 Result。
 *
 * 【为什么要用异常而不是返回 Result】
 * 三层结构里 Service 只管业务，不关心 HTTP；返回 Result 会让 Service 依赖 Web 层，
 * 而且每层都要判空传递，代码会变成一堆 if 嵌套。
 *
 * 【它在登录链路里的位置】
 *   AuthServiceImpl / WxApiClient 抛 BizException → 一路向上冒泡 →
 *   GlobalExceptionHandler 接住 → 转成 {"code":1001,"msg":"..."} 返回前端
 *
 * @author 校集后端
 */
// 【写法】@Getter 而不是手写 getCode()
// 【为什么】全局异常处理器要读 code 和 msg，getter 是必需的；@Getter 一行顶两段样板代码
// 【代价】Lombok 依赖 IDE 插件，否则编辑器里看不到方法（但编译没问题）
@Getter
public class BizException extends RuntimeException {
    // 【写法】继承 RuntimeException（非受检异常），而不是 Exception
    // 【为什么】受检异常会强制每一层方法签名写 throws，或者层层 try-catch：
    //   Controller → Service → Impl 全都要处理，代码被异常声明污染。
    //   而"token 过期""帖子不存在"这类是**可预期的业务结果**，不值得强制处理，
    //   交给最外层的 GlobalExceptionHandler 统一收口即可。
    // 【另一种】继承 Exception —— 会让所有调用方被迫写 throws，样板代码暴增。
    // 【代价】非受检异常编译器不提醒，可能被漏 catch；所以必须有一个全局处理器兜底。

    /** 业务返回码（对应 ResultCode 里的 code） */
    // 【为什么异常里要自带 code】
    //   异常会向上穿越很多层才到达全局处理器，如果只带一句 message，
    //   处理器就不知道该返回 1001 还是 1010。把 code 带在身上，才能"原样还原"成响应体。
    private final int code;

    /**
     * 用预定义错误码构造：message 取 ResultCode 里的默认文案。
     *
     * 例：throw new BizException(ResultCode.TOKEN_EXPIRED)
     *     → code=1002，msg="登录已过期，请重新登录"
     */
    public BizException(ResultCode rc) {
        // super(rc.getMsg())：把文案交给父类 Throwable 的 message 字段，
        // 这样日志里 e.getMessage() 也能看到原因
        super(rc.getMsg());
        this.code = rc.getCode();
    }

    /** 用预定义码，但覆盖提示文案（用于把具体原因告诉用户） */
    // 【为什么需要第二个构造器】
    //   同一个 code 往往对应多种具体原因。比如 1001（微信登录失败）可能是
    //   "凭证失效""太频繁""AppSecret 配错"——码相同，但提示必须不同，
    //   否则用户永远只看到笼统的"登录失败"。
    //   例：BizException.of(ResultCode.WX_LOGIN_FAIL, "登录凭证已失效，请重试")
    //       前端按 1001 统一处理，同时能把 msg 直接 toast 给用户。
    public BizException(ResultCode rc, String msg) {
        super(msg);
        this.code = rc.getCode();
    }

    /**
     * 快捷抛出：throw BizException.of(ResultCode.POST_NOT_FOUND);
     */
    // 【写法】静态工厂 of(...)，而不是到处 new BizException(...)
    // 【为什么】① 读起来像"抛出一个业务结果"，语义更清楚；
    //          ② 少写一次 new，减少噪音；
    //          ③ 以后想加 traceId、打日志等统一逻辑，只改工厂方法，调用方不动。
    // 【另一种】直接 new —— 完全可行，只是可读性与可维护性略差。
    public static BizException of(ResultCode rc) {
        return new BizException(rc);
    }

    /** 同上，但覆盖提示文案（最常用：同一个码、不同原因） */
    public static BizException of(ResultCode rc, String msg) {
        return new BizException(rc, msg);
    }
}
