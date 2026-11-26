package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 用户资料（自己看 / 别人看共用）。
 *
 * 【为什么不能直接返回 User 实体】
 * 实体里有 openid、unionid、status、deleted 这类内部字段，
 * 泄露 openid 等于把用户在微信里的唯一标识交给别人（可被用于撞库、画像）。
 *
 * 【一个 VO 服务两种场景（这是理解本类的关键）】
 * 同一个接口，看自己 和 看别人 返回的字段不同：
 *   · 看别人：只能拿到昵称、头像、认证状态、公开的扩展资料（受 privacy 逐项控制）
 *   · 看自己：额外拿到 birthday、privacy、postCount、dealCount（用于"我的"页面和编辑回填）
 * 过滤逻辑在 UserServiceImpl 里按"是不是本人"决定是否赋值 ——
 * 也就是说：**没权限的字段不是空字符串，而是压根不 set（保持 null）**。
 *
 * 【隐私设计的三层】
 *   ① 硬性不给：openid / unionid / status / 微信昵称原始值 —— 任何情况都不返回
 *   ② 逐项开关：gender/age/major/grade/college/campus/tags/availableTime/lookingFor
 *      每次返回前按 user_profile.privacy 里对应项判断，false 就置 null
 *   ③ 仅自己可见：birthday（可推算年龄）、privacy（可见性设置本身）、统计数字
 *
 * @author 校集后端
 */
// @Data：生成 getter/setter。序列化靠 getter
@Data
// @Schema：只影响接口文档
@Schema(description = "用户资料")
public class UserVO {

    // 字段：id —— 用户 ID
    // 取值：数字
    // 作用：前端跳"他主页"、发私信时要用
    // 可见性：所有人可见（本来就要靠它定位用户）
    @Schema(description = "用户 ID")
    private Long id;

    // 字段：nickname —— 昵称
    // 取值：1~32 字，新用户默认"校集用户"
    // 作用：各处展示（帖子卡片、聊天、详情页）
    // 可见性：所有人可见
    @Schema(description = "昵称")
    private String nickname;

    // 字段：avatar —— 头像 URL
    // 取值：完整 URL，或空字符串（未上传时前端用 initial 兜底）
    // 作用：展示头像
    // 可见性：所有人可见
    @Schema(description = "头像 URL")
    private String avatar;

    // 字段：initial —— 头像首字
    // 取值：昵称的第一个字，如"林"
    // 作用：前端做 monogram 头像（纯色背景 + 一个字），**避免下载真人照片**
    // 【为什么需要它】不是所有人都有头像；加载网络图片还有失败/慢/耗流量的问题，
    //   首字头像零成本、不会加载失败，视觉上也统一
    @Schema(description = "头像首字（前端 monogram 头像用，不下载真人照片）")
    private String initial;

    // 字段：intro —— 个人简介
    // 取值：一段自述文字，可为空
    // 作用：主页展示"这个人是谁"
    // 可见性：所有人可见（简介不参与 privacy 逐项控制）
    @Schema(description = "个人简介")
    private String intro;

    // 字段：creditScore —— 信用分
    // 取值：0~100，新用户 80
    // 作用：判断这个人可不可信（信用分 < 60 不能发帖）
    // 可见性：所有人可见（交易决策需要它）
    @Schema(description = "信用分 0-100")
    private Integer creditScore;

    // 字段：verifyStatus —— 认证状态码
    // 取值：0 未认证 / 1 待审核 / 2 已认证 / 3 驳回
    // 作用：前端显示"已认证"徽章；也可据此决定是否显示"未认证"提醒
    // 可见性：所有人可见（是否通过校园认证是重要的信任信号）
    @Schema(description = "认证状态：0未认证 1待审核 2已认证 3驳回")
    private Integer verifyStatus;

    // 字段：verified —— 是否已认证（布尔简化版）
    // 取值：true / false，等价于 verifyStatus == 2
    // 作用：前端只想画一个徽章，不想每次都写 verifyStatus === 2
    // 【为什么两个都给】verifyStatus 用来区分"待审核/驳回"（要显示不同提示），
    //   verified 用来快速判断"有没有徽章"。冗余一个字段，换前端少写一处判断
    @Schema(description = "是否已认证（前端只关心布尔，省得判 2）")
    private Boolean verified;

    // 字段：joinedAt —— 加入时间
    // 取值："2026-10-17"（只到天，不到时分秒）
    // 作用：主页显示"加入校集 20 天"
    // 注意字段名是 joinedAt 但数据来源是 user.created_at（注册即加入）
    @Schema(description = "发布时间格式化串，如 2026-10-17")
    private String joinedAt;

    /* ---------- 扩展资料（按 privacy 过滤） ---------- */
    // 【下面这些字段的可见性规则】
    // 每个字段在 user_profile.privacy 里都有一个同名开关（如 "major": true）。
    // 看别人时：开关为 false → 该字段返回 null；
    // 看自己时：**不看开关，全部返回**（否则自己都看不到自己填的资料）。

    // 字段：gender —— 性别
    // 取值：0 未知 / 1 男 / 2 女 / 3 保密
    // 作用：主页展示
    // 可见性：受 privacy.gender 控制
    @Schema(description = "性别：0未知 1男 2女 3保密")
    private Integer gender;

    // 字段：age —— 年龄
    // 取值：整数，如 20
    // 作用：主页展示
    // 【为什么返回 age 而不是 birthday】生日是强身份信息（可配合姓名精确定位到人），
    //   年龄只够用来展示。所以对外只给 age，birthday 仅自己可见
    // 可见性：受 privacy.age 控制
    @Schema(description = "年龄（由生日计算，不返回生日）")
    private Integer age;

    // 字段：birthday —— 生日
    // 取值："2004-05-12"
    // 作用：**只用于"编辑资料"页面回填**（用户要看到自己填的是什么才能改）
    // 可见性：仅自己可见（别人拿到的是 null）
    @Schema(description = "生日 yyyy-MM-dd；仅自己可见，用于编辑回填")
    private String birthday;

    // 字段：major —— 专业
    // 取值：如"计算机科学与技术"
    // 作用：判断能不能互助（同专业找人问问题）
    // 可见性：受 privacy.major 控制
    @Schema(description = "专业")
    private String major;

    // 字段：grade —— 入学年份
    // 取值：如 2023（表示 2023 级）
    // 作用：区分大一/大四，影响互助匹配
    // 注意存的是"入学年份"不是"年级"，因为年级会随时间变，入学年份不会
    // 可见性：受 privacy.grade 控制
    @Schema(description = "入学年份，如 2023")
    private Integer grade;

    // 字段：college —— 学院
    // 取值：如"计算机学院"
    // 作用：主页展示 + 互助匹配
    // 可见性：受 privacy.college 控制
    @Schema(description = "学院")
    private String college;

    // 字段：campus —— 校区
    // 取值：如"大学城"、"龙洞"
    // 作用：判断是否方便线下交易；也用于教务课表的校区推断
    // 可见性：受 privacy.campus 控制
    @Schema(description = "校区")
    private String campus;

    // 字段：tags —— 爱好/技能标签
    // 取值：字符串列表，如 ["摄影","修电脑","吉他"]
    // 作用：主页展示，方便找到同好/能帮忙的人
    // 可见性：受 privacy.tags 控制
    @Schema(description = "爱好/技能标签")
    private List<String> tags;

    // 字段：availableTime —— 可互助时间
    // 取值：自由文本，如"工作日晚上、周末全天"
    // 【为什么是 String 不是结构化时间段】用户表述本来就模糊（"晚上"是几点？），
    //   强行结构化会让填写变复杂；展示用的话，一段文字反而更自然
    // 可见性：受 privacy.availableTime 控制
    @Schema(description = "可互助时间")
    private String availableTime;

    // 字段：lookingFor —— 想找的类型
    // 取值：字符串列表，如 ["学习搭子","二手交易","技能交换"]
    // 作用：主页展示，说明这个人想在这做什么
    // 可见性：受 privacy.lookingFor 控制
    @Schema(description = "想找的类型")
    private List<String> lookingFor;

    // 字段：privacy —— 逐项可见性设置
    // 取值：Map，如 {"major":true,"grade":false,"campus":true}
    // 作用：编辑资料页要显示"每个字段旁边的可见开关"，必须把当前设置回传给前端
    // 可见性：**仅自己可见**（别人拿到 null）—— 否则等于公开"我不想让人看什么"，
    //   本身就是一种隐私泄露
    @Schema(description = "逐项可见性；仅自己可见")
    private Map<String, Boolean> privacy;

    /* ---------- 以下仅"自己看"时有值 ---------- */

    // 字段：postCount —— 发布数
    // 取值：整数
    // 作用："我的"页面显示"发布 12 条"
    // 可见性：仅自己可见（别人的统计不需要展示，且要额外查库）
    @Schema(description = "发布数（我的页面用）")
    private Long postCount;

    // 字段：dealCount —— 成交数
    // 取值：整数
    // 作用："我的"页面显示"成交 5 单"
    // 可见性：仅自己可见
    // 【注意】B 方案已移除订单链路，这个数字目前恒为 0 或来自历史数据，
    //   保留字段是为了不让前端页面报错（字段没了前端会显示 undefined）
    @Schema(description = "成交数（我的页面用）")
    private Long dealCount;
}
