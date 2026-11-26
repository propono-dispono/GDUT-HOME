package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 帖子详情 VO。
 *
 * 【它和 Post 实体的关系：不是复制，是加工】
 * Post 是"数据库怎么存"，这个类是"前端怎么显示"。两者字段几乎对不上，原因是这里做了四类加工：
 *   ① 换类型：price 从 BigDecimal 变成 String（去尾零后直接显示）
 *   ② 加字段：priceUnit、cta、notice、fields —— 数据库里根本没有，由后端算出来
 *   ③ 按用户裁剪：owner、favorited、joined —— 同一条帖子，不同人看到的值不同
 *   ④ 拆双份：images（完整 URL）+ imageKeys（原始 key）
 *
 * 【为什么不直接返回 Post】
 * Post 里有 userId、deleted、auditStatus、expireAt 等内部字段，前端用不上也不该看到；
 * 而且前端还需要 priceUnit、cta 这些"展示态"信息，实体里没有。
 * Entity → VO 的本质就是一次"脱敏 + 重新组织"。
 *
 * @author 校集后端
 */
// @Data：生成 getter/setter。序列化靠 getter，没有它前端收到的是 {}
@Data
// @Schema：只影响接口文档展示
@Schema(description = "帖子详情")
public class PostDetailVO {

    // 字段：id —— 帖子 ID
    // 取值：数字
    // 作用：前端"关闭/删除/参加拼单/收藏"等所有操作都要带上它
    private Long id;

    // 字段：biz —— 业务线代码
    // 取值：SECONDHAND / ERRAND / SKILL / RENTAL / LOST / GROUPBUY
    // 作用：前端据此决定卡片样式、详情页布局、按钮组合
    // 注意：实体里叫 bizType，VO 里改叫 biz —— VO 允许改名，这是它与前端约定的字段
    private String biz;

    // 字段：title —— 标题
    // 取值：字符串，最长 60 字
    // 作用：详情页大标题
    private String title;

    // 字段：content —— 正文
    // 取值：字符串，最长 5000 字，可能含 \n 换行
    // 作用：详情页主体内容
    private String content;

    @Schema(description = "金额字符串，去尾零")
    // 字段：price —— 金额（展示态）
    // 取值："15"、"15.5"、"0"；失物等无金额时为 null
    // 作用：直接显示用
    // 【为什么是 String 不是数字】数据库存 15.00，序列化成数字前端会看到"15.00"；
    //   这里已去尾零，前端直接拼接单位即可。代价是这个字段不能参与计算
    private String price;

    // 字段：priceUnit —— 金额单位
    // 取值："元"、"元/单"、"元/天"、"元/人"、"元/次"（按 bizType 决定）
    // 作用：与 price 拼成"15 元/天"
    // 【为什么后端给】前端写死就要为六条线各写一套判断，加业务线还得改小程序（要重新审核）
    private String priceUnit;

    // 字段：negotiable —— 是否可议价
    // 取值：0 = 一口价 / 1 = 可议价
    // 作用：详情页显示"可小刀"标签
    private Integer negotiable;

    @Schema(description = "图片完整 URL 列表")
    // 字段：images —— 图片完整地址
    // 取值：["https://xj.sxz-blog.xyz/api/files/2026/11/a1.jpg", ...]
    // 作用：直接给 <image src> 用，前端不用拼域名
    private List<String> images;

    @Schema(description = "图片 key 列表（前端如需重新上传/比对用）")
    // 字段：imageKeys —— 图片原始 key
    // 取值：["2026/11/a1.jpg", ...]，与 images 一一对应
    // 作用：用户编辑帖子时比对"哪张删了、哪张新加"；key 不含域名，永远稳定可比
    private List<String> imageKeys;

    // 字段：building —— 楼栋位置
    // 取值：如"东区 3 栋"
    // 作用：详情页显示交易地点
    private String building;

    // 字段：tags —— 标签
    // 取值：逗号分隔，如"9成新,可小刀"
    // 作用：详情页/卡片上展示成小标签
    private String tags;

    // 字段：meta —— 一行摘要
    // 取值：如"9成新 · 可小刀 · 东区 3 栋"
    // 作用：列表卡片副标题、详情页顶部副标题，由后端拼好，前端直接用
    private String meta;

    @Schema(description = "扩展字段，按业务线动态生成，前端直接按顺序渲染")
    // 字段：fields —— 扩展字段列表（详情页中部那几行"取件地 / 送达地 / 截止时间"）
    // 取值：[{k:"取件地", v:"菜鸟驿站"}, {k:"送达地", v:"3栋502"}]
    // 作用：六条线扩展字段完全不同，这里统一成"标签+值"列表
    // 【为什么这样设计】后端按 bizType 生成 2~4 项 → 前端只写一个循环渲染。
    //   新增业务线时前端代码一行不用改（也就不需要重新提审小程序）
    // 【代价】值全是字符串，失去类型，没法对某个字段做特殊渲染（例如给价格标红）
    private List<ExtFieldVO> fields;

    @Schema(description = "合规提示文案（如「线下自行结算，平台不经手资金」）")
    // 字段：notice —— 合规提示
    // 取值：一段固定文案，按业务线可能不同
    // 作用：详情页底部提示用户"平台不经手资金，谨防诈骗"
    // 【为什么后端下发】文案可能要按监管要求调整，写死在小程序里改一次就要走审核
    private String notice;

    @Schema(description = "主按钮文案：我想要 / 我来接单 / 预约 TA …")
    // 字段：cta —— 主按钮文字（Call To Action）
    // 取值：二手"我想要" / 跑腿"我来接单" / 技能"预约 TA" / 拼单"参加拼单"…
    // 作用：详情页底部主按钮文案，六条线各不同，由后端按 bizType 决定
    private String cta;

    @Schema(description = "发布人")
    // 字段：user —— 发布人信息
    // 取值：UserVO（昵称、头像、信用分、认证状态）
    // 作用：详情页展示"谁发的"，可点击进他主页
    // 【为什么不是 User 实体】实体里有 openid、unionid、status、deleted，绝不能给前端
    private UserVO user;

    @Schema(description = "我是否是发布者")
    // 字段：owner —— 我是不是作者
    // 取值：true / false
    // 作用：作者看到"关闭/编辑"，别人看到"我想要"
    // 【按用户裁剪】同一条帖子，不同人拿到不同的值
    private Boolean owner;

    // 字段：status —— 业务状态码
    // 取值：0待审 / 1展示中 / 2已下架 / 3已成交 / 4已关闭
    // 作用：前端判断是否还能操作（下架/关闭的不能"我想要"）
    private Integer status;

    // 字段：statusText —— 状态中文文案
    // 取值："待审核" / "展示中" / "已下架" / "已成交" / "已关闭"
    // 作用：直接显示，前端不用自己维护一份"码 → 文案"的映射表
    private String statusText;

    /** 驳回/下架原因：仅本人可见，公开信息流里为空 */
    // 字段：rejectReason —— 驳回/下架原因
    // 取值：如"图片含联系方式"、"内容与分类不符"；非本人或非驳回状态时为 null
    // 作用：作者看到后知道怎么改
    // 【安全要点】必须按"是不是本人"决定给不给值，否则等于把审核意见公开
    private String rejectReason;

    // 字段：viewCount —— 浏览量
    // 取值：整数
    // 作用：作者能看到热度；详情页每次访问由 SQL 自增
    private Integer viewCount;

    // 字段：createdAt —— 发布时间
    // 取值："2026-11-02 15:30:00"（已由 JacksonConfig 统一格式化）
    // 作用：详情页显示"发布于 xx"
    // 注意类型是 String 而不是 LocalDateTime —— 序列化时已转成字符串
    private String createdAt;

    @Schema(description = "是否已收藏（收藏功能接入后生效）")
    // 字段：favorited —— 我有没有收藏
    // 取值：true / false / null（未登录时为 null）
    // 作用：决定收藏按钮是实心还是空心
    private Boolean favorited;

    @Schema(description = "拼单：当前用户是否已参加（发起人恒为 true）；非拼单为 null")
    // 字段：joined —— 我参没参加这个拼单
    // 取值：true 已参加 / false 未参加 / null 非拼单业务线
    // 作用：决定按钮显示"参加拼单"还是"已参加"
    // 【注意 null 与 false 不同】非拼单给 null（前端不显示这个区块），
    //   拼单未参加才给 false。都当 false 处理的话，二手详情页会冒出"参加拼单"按钮
    private Boolean joined;

    @Schema(description = "拼单：是否已过截止时间；非拼单为 null")
    // 字段：groupbuyExpired —— 拼单是否已过截止
    // 取值：true 已过期 / false 未过期 / null 非拼单
    // 作用：过期后按钮置灰显示"已结束"，防止用户白点
    private Boolean groupbuyExpired;
}
