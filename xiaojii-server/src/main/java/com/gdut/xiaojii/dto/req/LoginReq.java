package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 微信登录请求。
 *
 * 【在登录链路中的位置】第 2 站（接参）
 * 前端 POST 的 JSON 由 Jackson 反序列化成这个对象，再交给 Controller。
 * 它只描述"数据长什么样、什么算合法"，不含任何业务逻辑。
 *
 * 【DTO 和 Entity 的区别（必须分清）】
 *   ① 对应什么：DTO 对应"接口的入参"；Entity 对应"数据库的一张表"
 *   ② 字段来源：DTO 是前端传什么就有什么；Entity 是表里有什么就有什么
 *   ③ 校验：DTO 才写校验注解；Entity 不该写接口校验
 *   ④ 能否返回前端：DTO 本来就是给前端看的；Entity 直接返回会泄露内部字段
 *
 * 一句话：Entity 面向数据库，DTO 面向接口，两者不要混用。
 *
 * @author 校集后端
 */
// ┌─ 【面试考点 1】@Data 在这里为什么是必需的？ ─────────────────────
// │ Q：DTO 上加 @Data 只是省 getter/setter 吗？去掉会怎样？
// │ A：不只是省代码 —— **Jackson 反序列化需要 setter（或字段可见）**。
// │    没有 setter 时，请求体里的 code/nickname 反序列化不进来，参数全是 null，
// │    表现为"前端明明传了，后端拿到是空"。加了 @Valid 后直接报"不能为空"，
// │    症状像"前端没传"，很容易误判。
// └────────────────────────────────────────────────────
// 【注解作用】@Data：Lombok 生成 getter/setter/toString/equals/hashCode。
//   这里**不是可选的** —— Jackson 反序列化需要 setter，去掉后前端传的字段进不来（全为 null）。
@Data   // = getter + setter + toString + equals + hashCode
// 【注解作用】@Schema：只影响接口文档，在 /api/doc.html 里显示该模型的名称与说明。
//   不加不影响任何运行逻辑。
@Schema(description = "微信登录请求")   // 仅用于接口文档展示
public class LoginReq {

    // ┌─ 【面试考点 2】@NotBlank / @NotNull / @NotEmpty 的区别？ ─────────
    // │ Q：String 判空该用哪个？
    // │ A：@NotNull 只判是否为 null（空字符串 "" 能通过）；
    // │    @NotEmpty 判 null 和长度 0（" " 空格能通过）；
    // │    @NotBlank 判 null、空串、以及**全是空白字符**（String 首选，最严格）。
    // └────────────────────────────────────────────────────
    // 【注解作用】@NotBlank：校验"不能为 null、不能是空串、不能全是空白字符"。
    //   必须配合 Controller 参数上的 @Valid 才会执行；单独写在这里等于没写。
    @NotBlank(message = "微信登录凭证不能为空")
    // message 是给用户看的文案，经 GlobalExceptionHandler 原样返回给前端。
    // 写文案的原则：说清"该做什么"，而不是"哪里错了"（"请重新登录"优于"code is null"）。
    @Schema(description = "wx.login() 返回的临时 code", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;
    // 这个字段是微信的一次性登录凭证（5 分钟有效、只能用一次、绑 appid）。
    // 名字必须和前端 JSON 的 key 完全一致（区分大小写），否则反序列化为 null。

    // ┌─ 【面试考点 3】@Size 校验的是字符长度还是字节长度？ ─────────────
    // │ Q：@Size(max = 32) 对中文昵称意味着什么？
    // │ A：校验的是 **String.length()**（Java 字符数），不是字节数，
    // │    也不等于数据库 VARCHAR(32) 的存储语义（utf8mb4 下 32 字符最多 128 字节）。
    // │    所以要和表字段长度对齐：DB 是 VARCHAR(32) 时这里写 32 正好；
    // │    若 DB 是 VARCHAR(16) 而这里写 32，超长会在 SQL 层报错或被截断。
    // └────────────────────────────────────────────────────
    // 【注解作用】@Size(max=32)：校验字符串长度上限 32 个字符（数 Java 字符，不是字节），
    //   超过就抛校验异常 → 由 GlobalExceptionHandler 转成 400 + message 文案。
    //   同样需要 @Valid 触发。要与数据库 VARCHAR(32) 对齐。
    @Size(max = 32, message = "昵称最多 32 个字")
    @Schema(description = "昵称，首次登录时可选（不传则用默认昵称）")
    private String nickname;
    // 可选字段：不传即 null，Service 里会兜底成 DEFAULT_NICKNAME（"校集用户"）。
    // 这说明"必填/可选"是写在校验注解上的，不是靠注释口头约定。
}
