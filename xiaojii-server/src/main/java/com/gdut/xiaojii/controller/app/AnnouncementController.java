package com.gdut.xiaojii.controller.app;

import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.entity.Announcement;
import com.gdut.xiaojii.service.AnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序端：公告。
 *
 * 【这一站在主线里的位置】
 * 站 1 里最简单的一个 Controller：1 个接口、零参数、只读。
 * 它把"Controller 只做三件事"体现得最干净 —— 没有校验、没有判断，只有一次转发。
 *
 * 【为什么这个接口未登录也能访问】
 * WebMvcConfig 的 APP_WHITE_LIST 里有 "/app/announcement/current"。
 * 原因是审核与合规要求：小程序首页必须能看到公示信息（学校公告），
 * 而审核人员打开小程序时并不会登录。所以这个接口必须免登录。
 *
 * 【这个文件里有一个"破例"，要能看出来】
 * 它返回的是 Result<Announcement> —— 直接返回了 Entity，与"绝不返回 Entity"的原则不一致。
 * 之所以能接受：公告表只有标题/正文/状态/时间，没有 openid、状态位这类敏感字段，
 * 泄露风险为零，单独建一个 VO 反而多一层无意义的转换。
 * 但这属于"知道自己在破例"，不是"不知道有这条规矩"。
 *
 * @author 校集后端
 */
@Tag(name = "07. 公告")
@RestController
@RequestMapping("/app/announcement")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    // ======================== 注解说明 ========================
    // @Tag                     只影响接口文档分组（归到"07. 公告"）
    // @RestController          注册成 Bean + 返回值序列化成 JSON（不做页面跳转）
    // @RequestMapping          类级前缀 /app/announcement
    // @RequiredArgsConstructor 生成只含 final 字段的构造器，Spring 用它注入 service
    // =========================================================

    /**
     * 当前生效的公告。
     *
     * 参数：无。不需要用户身份，也不需要分页。
     * 返回：Result<Announcement>。data 为 null 表示"当前没有生效的公告"，
     *   前端据此决定是否展示公告位（不是错误，所以 code 仍是 0）。
     *
     * 边界：如果一条公告都没有，返回 {code:0, msg:"ok", data:null}，而不是 404 ——
     *   "没有内容"和"接口不存在"是两件事，前端要能区分。
     */
    @Operation(summary = "当前公告", description = "没有生效公告时 data 为 null")
    @GetMapping("/current")
    public Result<Announcement> current() {
        return Result.ok(announcementService.current());
    }

    // ======================== 注解说明 ========================
    // @Operation  只影响接口文档（标题/描述）
    // @GetMapping  HTTP = GET、路径 = /current
    //              选 GET 是因为它只读、无副作用，可被缓存、可被浏览器直接访问
    // ======================== 面试考点 ========================
    // Q：为什么这个接口要设计成免登录？
    // A：合规要求 —— 小程序首页必须展示学校公示信息，而审核人员打开时未登录。
    //    实现上靠 WebMvcConfig 的白名单放行（不是靠注解）；
    //    注意 @SecurityRequirements 只是文档标记，和"是否放行"没有任何关系。
    //
    // Q：什么情况下可以直接返回 Entity，不用 VO？
    // A：判断标准不是"能不能"，而是"泄露风险有多大"。
    //    Announcement 只有 id/title/content/status/时间，没有敏感字段，
    //    也没有需要按用户裁剪的内容，省掉 VO 是合理简化。
    //    而 User 就不行：openid、unionid、status、deleted 一旦返回就是信息泄露。
    //    能说清"这里为什么可以破例"，比死记"必须用 VO"更有价值。
    // =========================================================
}
