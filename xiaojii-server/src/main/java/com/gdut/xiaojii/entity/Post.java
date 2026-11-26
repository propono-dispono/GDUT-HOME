package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 帖子实体（对应 post 表，6 条业务线共用一张表）。
 *
 * 【为什么六条业务线共用一张表】
 * 二手、跑腿、技能、租赁、失物、拼单，共同点是"一条内容 + 一个发布人 + 一个状态"。
 * 如果每条线建一张表，信息流就要 UNION 六张表、分页极难写、加一条线还要改 SQL。
 * 共用主表后，列表页只查 post 一张表就能混排，按 bizType 区分类型。
 *
 * 【差异字段放哪】
 * 各业务线独有的字段（取件地、截止时间、日租金…）放在 5 张扩展表里，
 * 一对一关联 post_id：
 *   post_errand（跑腿）/ post_lost（失物）/ post_skill（技能）
 *   post_rent（租赁）/ post_groupbuy（拼单）
 * 二手没有独有字段，所以是唯一没有扩展表的业务线。
 *
 * 【一个容易踩的坑：BizType 里有 hasExtTable，但它没用】
 * BizType 定义了 hasExtTable / orderable 两个标志位，看起来是用来驱动"要不要写扩展表"的，
 * 但全项目搜不到任何调用点（只有定义和赋值）。真正的判断在
 * PostServiceImpl 里写成 if (biz == BizType.ERRAND) ... else if 这样一条链。
 * 也就是说：新增一条业务线时，编译器不会提醒你去补扩展表逻辑，只能靠人记住。
 *
 * 【这样设计的代价】
 * 详情页要"主表 + 扩展表"查两次（PostServiceImpl.detail 里按 bizType 决定查哪张）；
 * 发帖要写两张表，所以必须有事务。
 *
 * @author 校集后端
 */
// 【写法】@Data：生成 getter/setter
// 【为什么】MyBatis 填值靠 setter、Jackson 序列化靠 getter，两个场景都需要
@Data
// 【写法】@TableName("post") 显式指定表名
// 【为什么】类名 Post 按驼峰转下划线也能推出 post，但显式写出来更稳：
//   将来类名或表名任一方改动，不会静默查错表（查错表不报错，只是查不到数据）
@TableName("post")
public class Post {

    // 【写法】@TableId(type = IdType.AUTO)
    // 【为什么】主键交给 MySQL 自增（建表语句 id 是 AUTO_INCREMENT）。
    //   不加这个注解时 MP 也认 id 字段，但不知道自增策略，插入后可能拿不到回填的 id
    @TableId(type = IdType.AUTO)
    private Long id;

    // 发布人。信息流要显示"谁发的"，详情页要判断"是不是我的"，都靠它
    private Long userId;

    /** SECONDHAND / ERRAND / SKILL / RENTAL / LOST / GROUPBUY */
    // 【为什么存字符串而不是数字】可读性优先：查库时一眼知道是哪条线，
    //   数字码要对照表才知道 1 是二手还是跑腿。
    // 【代价】比 TINYINT 占空间、且没有数据库层面的约束（靠 BizType.of() 校验）
    private String bizType;

    // 分类 ID，对应 CategoryEnum 里的分类（同一 bizType 下的一组二级分类）
    private Integer categoryId;

    // 标题。列表页展示用
    private String title;

    // 正文。详情页展示；发帖时会被敏感词校验（ContentCheckService）
    private String content;

    /** 售价/酬金/报价/日租金/拼单人均；LOST 为 null。仅记录，平台不经手资金 */
    // 【重要】同一个字段在六条线里语义都不同：
    //   二手=售价 / 跑腿=酬金 / 技能=报价 / 租赁=日租金 / 拼单=人均 / 失物=null
    //   之所以能共用一个字段，是因为"都有个金额"这个共性；差异由业务层解释。
    // 【为什么用 BigDecimal 而不是 double】金额必须精确，double 会有 0.1+0.2≠0.3 的精度问题
    private BigDecimal price;

    /** 0 不可议价 1 可议价 */
    // 用 Integer 而不是 boolean：数据库是 TINYINT，且能表达"未设置"（null）
    private Integer negotiable;

    /** JSON 数组字符串，最多 9 张，存的是上传接口返回的 key */
    // 【为什么存 JSON 字符串而不是建图片表】一条帖子最多 9 张图，且从不单独查图，
    //   建关联表属于过度设计；存成 JSON 一次读出来直接返回，简单够用。
    // 【代价】没法按图片查帖子、也没法做"图片去重"这类统计
    private String images;

    // 楼栋（如"东区 3 栋"）。校园场景按楼栋筛选比按经纬度实用
    private String building;

    /** 逗号分隔 */
    // 同样是"多值存字符串"的简化做法，理由与 images 相同
    private String tags;

    /** 0待审 1展示中 2已下架 3已成交 4已关闭 */
    // 【最容易混的字段之一】status 是"业务状态"（能不能被看到、卖没卖掉），
    //   与下面的 auditStatus（审核状态）是两个正交维度：
    //   一个帖子可以"审核通过（audit=2）但已成交（status=3）"。
    //   判断"能不能在信息流看到"要同时满足 status=1 且 auditStatus=2（或 1）。
    private Integer status;

    /** 0待审 1机器通过 2人工通过 3驳回 */
    // 【和 status 的区别】这条是"内容合规"维度：
    //   0 待审 → 1 机器过（先发后审） / 2 人工过 / 3 驳回
    //   审核由 AdminAuditServiceImpl 修改，驳回时下面 rejectReason 必须一起写
    private Integer auditStatus;

    // 驳回原因。只有 auditStatus=3 时才有值，前端展示给发布人看
    private String rejectReason;

    // 浏览量。每次详情页访问 +1（用 SQL 自增，不是先查再改，避免并发丢失更新）
    private Integer viewCount;

    /** 跑腿截止 / 拼单截止，到期由定时任务关闭 */
    // 只有 ERRAND 和 GROUPBUY 会用到；其他业务线为 null
    private LocalDateTime expireAt;

    /** 逻辑删除。MP 的全局配置已指定字段名，这里显式标注让 IDE 也能看懂 */
    // 【写法】@TableLogic
    // 【为什么】加上它，MP 会把 delete 语句自动改写成 UPDATE post SET deleted=1，
    //   所有 select 自动追加 WHERE deleted=0。
    // 【好处】数据可追溯（误删能恢复）、有关联数据时不会产生"孤儿"
    // 【代价】表会持续变大、deleted=1 的数据要定期归档；且唯一索引要考虑 deleted 参与
    //   （否则"删掉的标题"会占用唯一约束 —— 本项目 title 没加唯一索引，所以没这个问题）
    @TableLogic
    private Integer deleted;

    // 【写法】@TableField(fill = FieldFill.INSERT)
    // 【为什么】声明"插入时由 MybatisMetaObjectHandler 自动填充"，Service 里不用手写 setCreatedAt。
    //   不加则 createdAt 为 null，而 DDL 里是 NOT NULL，插入直接报错
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    // 【写法】fill = FieldFill.INSERT_UPDATE（插入和更新都填）
    // 【为什么】创建时要有值、每次修改也要刷新；用 INSERT_UPDATE 一个注解覆盖两个场景
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
