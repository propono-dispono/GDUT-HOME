package com.gdut.xiaojii.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体。
 *
 * 所有接口（含成功与失败）都返回这个结构，前端只需判断 code == 0。
 * 成功示例： { "code": 0, "msg": "ok", "data": { ... } }
 * 失败示例： { "code": 1001, "msg": "登录凭证已失效，请重试", "data": null }
 *
 * @param <T> 业务数据类型
 * @author 校集后端
 */
@Data
public class Result<T> implements Serializable {

    /** 返回码，0 表示成功 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 业务数据，失败时通常为 null */
    private T data;

    // ======================== 注解说明 ========================
    // @Data  生成 getter/setter/toString/equals/hashCode。
    //        这里主要为了序列化 —— Jackson 靠 getter 读字段值，
    //        没有 getter 时前端收到的会是 {}。
    // implements Serializable
    //        允许对象被放进 Redis 或走 RPC 序列化。当前接口链路用不到，
    //        但加上零成本，属于防御性写法。
    // ======================== 面试考点 ========================
    // Q：为什么要统一响应结构，而不是直接用 HTTP 状态码？
    // A：两种风格各有取舍，本项目选"包一层"的理由：
    //    ① 业务错误码颗粒度更细：HTTP 只有几十个状态码，业务需要 1001/1010/1030 这种
    //       细分码，前端据此做不同引导；
    //    ② 前端处理统一：一个 axios 拦截器判 code===0 就能兜住所有接口；
    //    ③ 中间件更宽容：网关/CDN 可能改写或缓存非 200 响应。
    //    代价是失去 HTTP 语义（HTTP 层永远 200），监控看不出错误率，
    //    要额外按 body 里的 code 统计 —— 这是已知缺点，要能主动说出来。
    //
    // Q：泛型 T 起什么作用？
    // A：同一个外壳能装任意类型 —— Result<LoginVO>、Result<List<PostItemVO>>、
    //    Result<PageResult<...>> 都行，且类型由调用处推断。写成 Object 就失去编译期约束。
    // =========================================================

    private Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    // ======================== 面试考点 ========================
    // Q：为什么构造器是 private，只能通过静态方法创建？
    // A：三个原因：
    //    ① 强制走预定义错误码：fail(ResultCode) 保证 code 与 msg 一定配套，
    //       避免"code 写 1001、msg 写成别的错误"这种低级错；
    //    ② 语义清晰：Result.ok(...) / Result.fail(...) 一眼看出意图，
    //       而 new Result(0, ...) 要读数字才知道 0 是成功；
    //    ③ 以后想统一加 traceId 或打日志，只改工厂方法，调用方不动。
    //    这就是"静态工厂方法"模式，JDK 的 List.of()、Optional.of() 同理。
    // =========================================================

    /** 成功且带数据 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.OK.getCode(), ResultCode.OK.getMsg(), data);
    }

    /** 成功但无数据 */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /** 按预定义错误码失败 */
    public static <T> Result<T> fail(ResultCode rc) {
        return new Result<>(rc.getCode(), rc.getMsg(), null);
    }

    /** 按预定义错误码失败，但覆盖提示文案（用于把具体原因告诉用户） */
    public static <T> Result<T> fail(ResultCode rc, String msg) {
        return new Result<>(rc.getCode(), msg, null);
    }

    /** 完全自定义失败 */
    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    // ======================== 说明 ========================
    // 1. ok(T data) 方法名前的 <T> 是"泛型方法声明"，让返回类型由调用处推断，
    //    所以 Result.ok(loginVO) 自动就是 Result<LoginVO>，不用强转。
    // 2. ok() 用于"只关心成功与否"的接口（如退出登录），data 为 null。
    // 3. fail(ResultCode, msg) 的典型用法：
    //      BizException.of(ResultCode.WX_LOGIN_FAIL, "登录凭证已失效，请重试")
    //    code 用通用分类（1001），msg 给精确原因，前端可按 1001 统一处理、
    //    同时把 msg 直接 toast 给用户。
    // ======================== 面试考点 ========================
    // Q：失败时 data 为什么统一给 null，而不是保留部分数据？
    // A：可以带，但要有约定。本项目约定"失败一律 data=null"，原因信息全放 msg：
    //    好处是前端判断简单（code!==0 即失败，不存在"半成功"状态）；
    //    代价是需要结构化错误详情（如表单逐字段报错）时，只能靠 msg 拼字符串。
    //    本项目用不到，属于可接受的简化。
    // =====================================================
}
