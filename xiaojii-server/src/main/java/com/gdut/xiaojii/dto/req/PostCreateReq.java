package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发布帖子请求。
 *
 * 【六条业务线共用一个 DTO，字段是"并集"】
 * 这个类里既有二手用的 price、跑腿用的 pickupAddr、租赁用的 rentPrice、拼单用的 targetCount……
 * 每条业务线只填自己那部分，Service 按 bizType 取需要的字段写对应扩展表，其余忽略。
 *
 * 【为什么扩展字段平铺，而不是用一个 Map】
 * Map 会丢失类型与校验能力：前端把 targetCount 传成字符串也能塞进去，
 * 直到写库那一刻才报错，错误信息还看不出是哪个字段。
 * 平铺成具体字段后，每个字段都能挂校验注解、IDE 也能提示、接口文档也能自动生成。
 * 代价是：字段会越堆越多（现在 27 个），且"哪条线该填哪些"在 DTO 上看不出来。
 *
 * 【校验分两层（理解这个类的关键）】
 *   第一层（本类）：@NotBlank / @Size / @DecimalMin —— 只管"六条线通用"的规则。
 *   第二层（Service 的 checkBizRequiredFields）：只管"某条线特有"的必填，
 *       比如跑腿必须有取件地、拼单目标人数至少 2。这些没法用注解表达（要按 bizType 分支）。
 *   所以：**DTO 上没写 @NotBlank 的字段，不代表可以不填**，要看 Service 的判断。
 *
 * @author 校集后端
 */
// @Data：生成 getter/setter。Jackson 反序列化需要 setter，没有它所有字段都是 null
@Data
// @Schema：只影响 Swagger 文档展示
@Schema(description = "发布帖子请求")
public class PostCreateReq {

    /* ==================== 六条线共用的公共字段 ==================== */

    // 字段：bizType —— 业务线代码
    // 取值：SECONDHAND 二手闲置 / ERRAND 跑腿代办 / SKILL 技能互助
    //       RENTAL 物品租赁 / LOST 失物招领 / GROUPBUY 校园拼单（六选一）
    // 作用：整条链路的开关 —— 决定写哪张扩展表、详情页显示什么按钮文案、金额单位是什么
    // 注解：@NotBlank 不能为空（否则 Service 里 BizType.of(null) 直接抛 400）
    @NotBlank(message = "请选择业务类型")
    @Schema(description = "SECONDHAND/ERRAND/SKILL/RENTAL/LOST/GROUPBUY",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String bizType;

    // 字段：categoryId —— 二级分类 ID
    // 取值：整数，来自 GET /app/post/categories 返回的 id（同一 bizType 下的一组分类）
    // 作用：前端按分类筛选信息流；列表页显示分类标签
    // 注解：@NotNull（Integer 没有"空字符串"这种状态，所以用 NotNull 而不是 NotBlank）
    @NotNull(message = "请选择分类")
    @Schema(description = "二级分类 ID，见 /app/post/categories")
    private Integer categoryId;

    // 字段：title —— 标题
    // 取值：1~60 个字符，如"出 9 成新自行车"
    // 作用：信息流卡片上最显眼的一行；同时会被敏感词校验（ContentCheckService）
    // 注解：@NotBlank 不能为空；@Size(max=60) 与 DDL 的 VARCHAR(60) 对齐
    @NotBlank(message = "请填写标题")
    @Size(max = 60, message = "标题最多 60 个字")
    @Schema(description = "标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    // 字段：content —— 正文
    // 取值：1~5000 个字符，纯文本（换行用 \n，不支持富文本）
    // 作用：详情页主体；同样过敏感词校验
    // 注解：@NotBlank 不能为空；@Size(max=5000) 是产品定的可读上限
    @NotBlank(message = "请填写正文")
    @Size(max = 5000, message = "正文最多 5000 个字")
    @Schema(description = "正文", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    // 字段：price —— 金额，但六条线语义不同
    // 取值：二手=售价 / 跑腿=酬金 / 技能=报价 / 租赁=日租金 / 拼单=人均 / 失物=不填(null)
    // 作用：列表和详情展示；入库到 post.price 同一个字段
    // 注解：@DecimalMin("0") 不能为负（BigDecimal 要用 DecimalMin，不能用 @Min）
    //       注意这里没有 @NotNull —— 失物招领本来就没金额，哪条线必填由 Service 判断
    @DecimalMin(value = "0", message = "金额不能为负")
    @Schema(description = "售价/酬金/报价/日租金/人均；失物不填")
    private BigDecimal price;

    // 字段：negotiable —— 是否可议价
    // 取值：0 = 不可议价（一口价） / 1 = 可议价（可小刀）
    // 作用：卡片上显示"可小刀"标签，作为交易参考
    // 注解：@Min(0) @Max(1) 限定只能取这两个值
    //       用 Integer 而不是 Boolean，是为了和数据库 TINYINT、前端 0/1 直接对上
    @Min(0)
    @Max(1)
    @Schema(description = "是否可议价：0否 1是")
    private Integer negotiable;

    // 字段：images —— 图片 key 列表
    // 取值：最多 9 个字符串，每个是上传接口返回的 key（如 "2026/11/a1b2c3.jpg"）
    // 作用：入库时由 Service 转成 JSON 字符串存进 post.images
    // 注解：@Size(max=9) 作用在 List 上时校验的是**元素个数**（9 张图），不是字符长度
    @Size(max = 9, message = "最多上传 9 张图片")
    @Schema(description = "图片 key 列表（先调 /app/upload）")
    private List<String> images;

    // 字段：building —— 楼栋位置
    // 取值：如"东区 3 栋"、"西区宿舍"，最长 32 字
    // 作用：校园场景就近交易；列表页展示
    @Size(max = 32)
    @Schema(description = "楼栋位置，如 东区宿舍")
    private String building;

    // 字段：tags —— 标签
    // 取值：逗号分隔的字符串，如"9成新,可小刀,急出"，整体最长 120 字
    // 作用：展示在卡片上，帮用户快速判断（不做检索，检索靠分类和关键词）
    @Size(max = 120)
    @Schema(description = "标签，逗号分隔")
    private String tags;

    /* ==================== 以下为各业务线独有的扩展字段 ==================== */
    // 【为什么这些字段都没有 @NotBlank】
    // 因为它们只对"某一条业务线"必填，而 DTO 不知道 bizType。
    // 真正的必填校验在 PostServiceImpl.checkBizRequiredFields 里按业务线分支判断。

    /* ---------- ERRAND 跑腿 ---------- */

    // 字段：pickupAddr —— 取件地
    // 取值：如"菜鸟驿站(东区)"、"一饭门口"
    // 作用：接单的人要知道去哪拿东西；入库 post_errand.pickup_addr
    // 必填：跑腿线必填（Service 校验）
    @Schema(description = "跑腿：取件地")
    private String pickupAddr;

    // 字段：deliverAddr —— 送达地
    // 取值：如"3 栋 502"、"图书馆二楼"
    // 作用：送到哪；入库 post_errand.deliver_addr
    // 必填：跑腿线必填
    @Schema(description = "跑腿：送达地")
    private String deliverAddr;

    // 字段：deadline —— 最晚送达时间
    // 取值：日期时间，如 2026-11-03 18:00
    // 作用：超时未送达可被举报；同时决定 post.expire_at（到期由定时任务关闭）
    // 必填：跑腿线必填
    @Schema(description = "跑腿：最晚送达时间")
    private LocalDateTime deadline;

    // 字段：advancePay —— 是否需要垫付
    // 取值：0 = 不需垫付（对方先给钱） / 1 = 需要垫付（接单的人先掏钱买）
    // 作用：影响用户是否愿意接单；平台不经手资金，垫付是私下结算
    @Schema(description = "跑腿：是否需垫付 0否 1是")
    private Integer advancePay;

    /* ---------- LOST 失物 ---------- */

    // 字段：lostType —— 失物类型
    // 取值：1 = 寻物（我丢东西了） / 2 = 拾物（我捡到东西了）
    // 作用：决定详情页文案措辞和按钮（"我捡到了" vs "这是我的"）
    // 必填：失物线必填
    @Schema(description = "失物：1寻物 2拾物")
    private Integer lostType;

    // 字段：itemName —— 物品名称
    // 取值：如"黑色雨伞"、"校园卡"、"AirPods 充电盒"
    // 作用：列表标题之外的额外识别信息；入库 post_lost.item_name
    // 必填：失物线必填
    @Schema(description = "失物：物品名称")
    private String itemName;

    // 字段：lostPlace —— 丢失/拾获地点
    // 取值：如"二饭三楼"、"教 3 楼下"
    // 作用：帮对方判断是不是自己的东西
    // 必填：失物线必填
    @Schema(description = "失物：地点")
    private String lostPlace;

    // 字段：lostTime —— 丢失/拾获时间
    // 取值：日期时间
    // 作用：时间越近越可能找回，列表可按此排序
    // 必填：失物线必填
    @Schema(description = "失物：时间")
    private LocalDateTime lostTime;

    /* ---------- SKILL 技能 ---------- */

    // 字段：serviceType —— 技能类目
    // 取值：如"代做 PPT"、"修电脑"、"辅导高数"、"代取快递"
    // 作用：详情页展示，让人一眼知道能提供什么服务
    // 必填：技能线必填
    @Schema(description = "技能：类目")
    private String serviceType;

    // 字段：deliveryDays —— 交付天数
    // 取值：整数，如 1 表示当天交付、3 表示三天内
    // 作用：给需求方一个预期；超时可被投诉
    // 必填：技能线必填
    @Schema(description = "技能：交付天数")
    private Integer deliveryDays;

    /* ---------- RENTAL 租赁 ---------- */

    // 字段：rentPrice —— 日租金
    // 取值：金额，如 15 表示 15 元/天
    // 作用：与主字段 price 重叠 —— 填了会覆盖 price（历史遗留，两字段表达同一件事）
    // 必填：租赁线必填其一（price 或 rentPrice）
    @Schema(description = "租赁：日租金（与 price 二选一，填了会覆盖 price）")
    private BigDecimal rentPrice;

    // 字段：rentUnit —— 计价单位
    // 取值："天" 或 "周"
    // 作用：拼在租金后面显示"15 元/天"；决定按什么周期计价
    // 必填：租赁线必填
    @Schema(description = "租赁：计价单位 天/周")
    private String rentUnit;

    // 字段：deposit —— 押金
    // 取值：金额，可为 0
    // 作用：仅展示给双方参考，平台不经手资金（线下自行结算）
    @Schema(description = "租赁：押金，仅记录")
    private BigDecimal deposit;

    // 字段：minDays —— 起租天数
    // 取值：整数，如 3 表示至少租 3 天
    // 作用：过滤"只租 1 天"的无效需求
    @Schema(description = "租赁：起租天数")
    private Integer minDays;

    /* ---------- GROUPBUY 拼单 ---------- */

    // 字段：targetCount —— 目标人数
    // 取值：整数 ≥ 2（含发起人自己）
    // 作用：满员后自动关帖（post.status → 4）；决定进度条"3/5"
    // 必填：拼单线必填，且必须 ≥2（"至少 2"由 Service 判断，注解表达不了）
    @Schema(description = "拼单：目标人数（含发起人），至少 2")
    private Integer targetCount;

    // 字段：groupbuyDeadline —— 拼单截止时间
    // 取值：日期时间
    // 作用：到期未成团自动关闭；详情页显示倒计时
    // 必填：拼单线必填
    @Schema(description = "拼单：截止时间")
    private LocalDateTime groupbuyDeadline;

    // 字段：withErrand —— 成团后是否需要代取
    // 取值：0 = 不需要 / 1 = 需要（如拼奶茶，需要有人去拿）
    // 作用：决定成团后是否额外产生跑腿需求
    @Schema(description = "拼单：是否需要代取 0否 1是")
    private Integer withErrand;

    // 字段：pickupPoint —— 自提点
    // 取值：如"一饭门口"、"菜鸟驿站"
    // 作用：成团后大家去哪里拿货
    @Schema(description = "拼单：自提点")
    private String pickupPoint;
}
