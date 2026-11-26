package com.gdut.xiaojii.controller.app;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.dto.vo.NoticeVO;
import com.gdut.xiaojii.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 小程序端：站内通知。
 *
 * 【这一站在主线里的位置】
 * 站 1 里最典型的"需要登录 + 需要分页"的 Controller，4 个接口覆盖了三种常见形态：
 *   ① 分页列表（list）
 *   ② 简单计数（unreadCount）
 *   ③ 带路径参数的写操作（read / readAll）
 *
 * 【本文件要重点掌握的一件事】
 * 它用的是"另一种"参数校验方式：类上加 @Validated，方法参数上直接写 @Min/@Max。
 * 这条链路和 @Valid + RequestBody 完全不同，走的是 ConstraintViolationException，
 * 而不是 MethodArgumentNotValidException。两条链路都必须被全局异常处理器覆盖，
 * 漏一条就会返回 500。详见 GlobalExceptionHandler。
 *
 * @author 校集后端
 */
@Tag(name = "05. 站内通知")
@Validated
@RestController
@RequestMapping("/app/notice")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    // ======================== 注解说明 ========================
    // @Tag                     只影响接口文档分组
    // @Validated               开启"方法参数级校验"。类上不加它，下面方法参数上的
    //                          @Min/@Max 全部不生效（Spring 需要这个标记才会给方法套校验代理）
    // @RestController          注册成 Bean + 返回值序列化成 JSON
    // @RequestMapping          类级前缀 /app/notice
    // @RequiredArgsConstructor 生成 final 字段构造器，注入 NoticeService
    // ======================== 面试考点 ========================
    // Q：类上写 @Validated、参数上写 @Valid，是重复了吗？
    // A：不是，两者管的东西不同：
    //    @Valid 写在"对象参数"上 → 校验对象内部的字段（如 LoginReq 的 @NotBlank）；
    //    @Validated 写在"类"上   → 让方法参数上的约束注解（@Min/@Max）生效。
    //    本类有 @RequestParam @Min(1)，所以必须有类级 @Validated；
    //    PostController 没写，因为它只在 DTO 字段上校验，参数上没有约束注解。
    //
    // Q：校验失败抛的异常一样吗？
    // A：三条链路各不相同，GlobalExceptionHandler 必须都处理，漏一条就是 500：
    //    @Valid + @RequestBody 对象  → MethodArgumentNotValidException
    //    @Valid + 非 body 对象参数    → BindException
    //    @Validated + 方法参数约束    → ConstraintViolationException
    // =========================================================

    /**
     * 我的通知列表（分页）。
     *
     * 参数：
     *   page —— 页码，从 1 开始，默认 1，最小 1（@Min 校验）
     *   size —— 每页条数，默认 20，范围 1~100（@Min/@Max 校验）
     *   两个参数都来自 URL 查询串（?page=1&size=20），不是请求体。
     *
     * 返回：Result<PageResult<NoticeVO>>
     *   PageResult 里是 records / total / page / size，前端靠 total 算总页数。
     *
     * 边界：
     *   ① page 传 0 或负数 → 被 @Min(1) 拦下，返回 400；
     *   ② size 传 999 → 被 @Max(100) 拦下，返回 400（这里挡在门口，
     *      分页插件的 maxLimit=100 是第二道闸，两层都保留）；
     *   ③ 不传这两个参数 → 用默认值 1 和 20，不报错。
     */
    @Operation(summary = "我的通知列表", description = "按时间倒序，含已读/未读标记")
    @GetMapping("/list")
    public Result<PageResult<NoticeVO>> list(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer size) {
        return Result.ok(noticeService.list(UserContext.requireUserId(), page, size));
    }

    // ======================== 注解说明 ========================
    // @Operation            只影响接口文档（标题/描述）
    // @GetMapping           HTTP = GET、路径 = /list
    // @Parameter            只影响接口文档，给参数加说明文字
    // @RequestParam         defaultValue="1"：从查询串取值，不传就用默认值
    //                       与 @RequestBody 的区别：数据来自 ?page=1，不是 JSON body
    // @Min / @Max           值域校验，需要类上的 @Validated 才生效
    // ======================== 面试考点 ========================
    // Q：userId 为什么由 Controller 取、再传给 Service，而不是让 Service 自己取？
    // A：两种写法本项目都有：
    //    ① 显式传参（本类、Chat、Report）：Service 不依赖 Web 上下文，
    //       单测可以直接传任意 userId，不用模拟 HTTP 请求；
    //    ② 内部自取（PostServiceImpl 有 6 处）：Controller 更薄，
    //       但 Service 绑死 ThreadLocal，脱离 Web 环境无法测试。
    //    能说出"我知道这里有取舍、以及各自的代价"，比背标准答案有价值。
    // =========================================================

    /**
     * 未读条数（给底栏红点用）。
     *
     * 参数：无。
     * 返回：Result<Map<String, Long>>，结构是 {"count": 3}。
     *
     * 边界：没有未读时返回 {"count": 0}，不是 null —— 前端直接取 count 即可，不用判空。
     */
    @Operation(summary = "未读条数", description = "给底栏红点用")
    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount() {
        Map<String, Long> data = new HashMap<>(2);
        data.put("count", noticeService.unreadCount(UserContext.requireUserId()));
        return Result.ok(data);
    }

    // ======================== 注解说明 ========================
    // @Operation  只影响接口文档
    // @GetMapping HTTP = GET、路径 = /unread-count
    // ======================== 说明 ========================
    // 为什么用 Map 而不是专门建一个 UnreadCountVO：
    //   只有一个字段，建 Class 的成本高于收益；
    //   但字段超过 2 个就应该建 VO，否则前端无法从代码上知道有哪些 key。
    //   new HashMap<>(2) 的 2 是初始容量，避免扩容。
    // =========================================================

    /**
     * 标记单条已读。
     *
     * 参数：id —— 通知 ID，来自路径 /{id}/read，例如 POST /app/notice/12/read
     * 返回：Result<Void>，只关心成功与否。
     *
     * 边界（重要）：这里只传了 id，没有传 userId。
     *   Service 内部必须做"这条通知是不是我的"校验，
     *   否则任何人都能把别人的通知标记成已读（越权）。
     *   Controller 层不做这个判断，是分层原则；但你必须知道它发生在 Service 里。
     */
    @Operation(summary = "标记单条已读", description = "只能操作自己的通知")
    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        noticeService.markRead(UserContext.requireUserId(), id);
        return Result.ok();
    }

    // ======================== 注解说明 ========================
    // @Operation    只影响接口文档
    // @PostMapping  HTTP = POST、路径 = /{id}/read
    //               虽然只是"改已读"，但它修改了服务端数据（有副作用），所以不用 GET
    // @PathVariable 从 URL 路径取值：/12/read 里的 12 绑定到 id
    //               不做类型校验，传 /abc/read 会抛类型转换异常（被全局处理器转成 400）
    // =========================================================

    /**
     * 全部标记已读。
     *
     * 参数：无（用户来自 UserContext）。
     * 返回：Result<Void>。
     *
     * 边界：只能清自己的；Service 内部按 userId 过滤。
     */
    @Operation(summary = "全部标记已读")
    @PostMapping("/read-all")
    public Result<Void> readAll() {
        noticeService.markAllRead(UserContext.requireUserId());
        return Result.ok();
    }

    // ======================== 注解说明 ========================
    // @Operation   只影响接口文档
    // @PostMapping HTTP = POST、路径 = /read-all
    // =========================================================
}
