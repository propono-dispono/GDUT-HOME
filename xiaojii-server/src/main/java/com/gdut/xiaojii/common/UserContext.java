package com.gdut.xiaojii.common;

/**
 * 当前登录用户上下文（基于 ThreadLocal）。
 *
 * 【它解决什么问题】
 * 拦截器解析出 userId 之后，Controller / Service / Mapper 各层都可能要用"当前用户是谁"。
 * 如果靠方法参数层层传递，每个方法签名都要加一个 userId 参数，改动面极大。
 * 放进 ThreadLocal 后，同一线程内任何地方都能直接 UserContext.getUserId() 拿到。
 *
 * 【ThreadLocal 是什么】
 * 可以理解成"每个线程自己的储物柜"：
 *   · 线程 A 往里放 100，线程 B 放 200，两个人互不影响；
 *   · 同一个线程内，任何方法都能打开自己的柜子取出刚才放的东西。
 * 注意 HOLDER 是 static 的，但 static 只表示"全类共用一个储物柜编号"，
 * 柜子里的东西仍然是每个线程各存各的 —— 这点最容易误解。
 *
 * 【完整流转】
 *   请求进入 → AuthInterceptor 解析 JWT → setUserId(userId)
 *   → Controller/Service 调 getUserId()/requireUserId() 取用
 *   → 请求结束 → AuthInterceptor.afterCompletion 调 clear()
 *
 * 【为什么必须 clear（真实风险）】
 * Tomcat 用线程池处理请求，同一个线程会连续服务很多用户。
 * 如果上一个用户的 userId 没清掉，下一个没带令牌的请求就会读到"上一个用户"的 ID ——
 * 这是**数据越权**，不是简单的脏数据。
 *
 * @author 校集后端
 */
// 【写法】final class + 私有构造器
// 【为什么】纯工具类，不允许被继承（避免子类改语义）、不允许 new（所有方法都是 static）。
//   这只是编码约束，编译期就能阻止误用。
public final class UserContext {

    // 【写法】static final ThreadLocal<Long>，只存 userId
    // 【为什么】static 是为了全局唯一入口（所有类都调 UserContext.xxx）；
    //   ThreadLocal 保证"每个线程一份独立的值"。
    // 【这里存 Long 而不是 User 对象】只传主键，需要用户详情时再查 ——
    //   避免把实体缓存进 ThreadLocal 导致"数据变了但缓存没变"的不一致。
    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    // 私有构造器：外部无法 new UserContext()，只能通过静态方法使用
    private UserContext() {
    }

    // 由 AuthInterceptor 在请求进入时调用，写入当前用户 ID
    public static void setUserId(Long userId) {
        HOLDER.set(userId);
    }

    /**
     * 取当前用户 ID，未登录返回 null（用于"可登录可不登录"的接口）。
     */
    // 【写法】getUserId() 容许 null，requireUserId() 强制非空，两个方法并存
    // 【为什么】接口分两类：一类"登录后体验更好"（如信息流，未登录也能看），
    //   调用方需要知道"有没有登录"这个事实，所以要能拿到 null；
    //   另一类"必须登录"（如发帖），拿不到就该直接 401，不该让每个调用方各写一次判空。
    // 【代价】两个方法容易用错 —— 需要登录的接口误用 getUserId()，
    //   会得到 null 然后引发 NPE，而不是清晰的 401。用之前先想清楚接口属于哪类。
    /** 取userid */
    public static Long getUserId() {
        return HOLDER.get();
    }

    /**
     * 取当前用户 ID，未登录直接抛 401。
     * 需要登录的接口用它，省掉每处判空。
     */
    // 【写法】内部判空后抛 BizException(UNAUTHORIZED)
    // 【为什么】把"必须登录"这个断言收口到一处：
    //   各 Service 直接 requireUserId()，不用每处写 if (id == null) throw ...
    //   抛出的 401 会被 GlobalExceptionHandler 接住转成 {code:401,msg:"请先登录"}
    /** 获取并校验userid */
    public static Long requireUserId() {
        Long id = HOLDER.get();
        if (id == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return id;
    }

    /** 请求结束务必调用，防止线程复用导致串号 */
    // 【写法】HOLDER.remove()，而不是 HOLDER.set(null)
    // 【为什么】set(null) 只是把值置空，ThreadLocalMap 里那条 Entry 仍然存在
    //   （key 是弱引用、value 是强引用），线程长时间存活时会累积成内存泄漏；
    //   remove() 才会真正删掉这条记录。这是 ThreadLocal 的标准用法。
    // 【谁调它】AuthInterceptor.afterCompletion —— 放在 afterCompletion 而不是 postHandle，
    //   是因为 postHandle 在 Controller 抛异常时不会执行，而 afterCompletion 一定会执行。
    /** 清空 */
    public static void clear() {
        HOLDER.remove();
    }
}
