package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 登录返回。
 *
 * 【在登录链路中的位置】最后一站（出口）
 * AuthServiceImpl 组装好它，AuthController 用 Result.ok(...) 包一层，
 * 最终由 Jackson 序列化成 JSON 给小程序。
 *
 * 【为什么不能直接把 User 实体返回前端】
 * User 表里有 openid、unionid、status、deleted，还有将来可能加的备注字段 —— 全都会暴露。
 * VO 只挑"前端该看到的"，并且可以加数据库里没有的字段（如 firstLogin）。
 * Entity 转 VO 的本质，是一次"脱敏 + 重新组织"。
 *
 * @author 校集后端
 */
// 【注解作用】@Data：生成 getter/setter。**序列化成 JSON 依赖 getter** ——
//   没有 getter 时 Jackson 输出的是空对象 {}，前端拿到的字段全是 undefined。
@Data
// 【注解作用】@Schema：只影响接口文档（模型名 + 字段说明）。
//   本类字段上另外 6 个 @Schema 同理，全部只在 /api/doc.html 里显示，不参与任何运行逻辑。
@Schema(description = "登录结果")
public class LoginVO {

    // ┌─ 【面试考点 1】DTO、VO、Entity 三者怎么区分？ ────────────────────
    // │ Q：项目里为什么有 req / vo / entity 三套长得差不多的类？
    // │ A：三个方向、三种职责：
    // │    · req（DTO）—— 前端 → 后端，带校验注解，只包含"允许前端传"的字段；
    // │    · vo        —— 后端 → 前端，已脱敏、已加工，可以含数据库没有的字段；
    // │    · entity    —— 与数据库表一一对应，只服务持久层。
    // │    混用的后果：① 直接返回 entity → 泄露内部字段（openid、status、deleted）；
    // │    ② 直接用 entity 接参 → 前端可以伪造 id、creditScore 等不该由它控制的字段。
    // └────────────────────────────────────────────────────
    @Schema(description = "JWT 令牌，后续请求放在 Authorization: Bearer {token}")
    private String token;
    // 前端拿到后必须存起来（小程序一般存 storage），之后每个请求放到请求头。
    // 注意这里是"裸 token"，不含 "Bearer " 前缀 —— 前缀由前端拼接，或后端拼接后再下发。
    // 若前端把前缀也存进去，请求头会变成 "Bearer Bearer xxx"，拦截器解析后校验失败。

    @Schema(description = "用户 ID")
    private Long userId;
    // 前端用它调"看某人主页"这类接口。注意：本项目主键是自增 BIGINT（不是雪花 ID），
    // 数值还很小，暂时不会触发 JS 精度问题；但 JacksonConfig 已经统一把 Long 转成 String
    // 来兜底（学到阶段 2 会看到）。

    @Schema(description = "认证状态：0未认证 1待审核 2已认证 3驳回")
    private Integer verifyStatus;
    // 这四个状态值来自 User 类的常量（VERIFY_NONE/VERIFY_PENDING/VERIFY_PASS/VERIFY_REJECT）。
    // 前端据此决定首页是显示"去认证"还是"发帖"。
    // 边界：状态值是 Integer 而非枚举，前端拿到数字要自己映射文案 ——
    //   若新增状态值，前端不改就会显示"未知状态"，这是这类设计的常见维护成本。

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "信用分")
    private Integer creditScore;
    // 新用户初始 80 分（不是 100），留给"违规扣分"的操作空间。

    @Schema(description = "是否首次登录（新注册），前端可据此引导去认证")
    private Boolean firstLogin;
    // ┌─ 【面试考点 2】为什么需要 firstLogin 这种"数据库里没有的字段"？ ──────
    // │ Q：用户是不是新注册，查一下 created_at 不就知道了？
    // │ A：这里指的不是"注册时间"，而是**本次请求是否触发了建档**。
    // │    前端需要在"刚注册"这一刻弹引导（去认证/领新人提示），
    // │    这是一个**请求级的事实**，不是持久状态；DB 里没有也不需要这一列。
    // │    这正是 VO 存在的意义：可以承载"计算出来的、只对本次响应有意义"的字段。
    // └────────────────────────────────────────────────────
}
