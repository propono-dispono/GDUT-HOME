package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 物品租赁扩展表 post_rent 的实体，一条帖子最多一行。
 *
 * 谁写：PostServiceImpl 的 saveExt（业务线是 RENTAL 时插一行）
 * 谁读：PostServiceImpl 的 detail（第 721 行 selectById）
 *
 * 【合规要点】押金只是记录，平台不代收、不代管，也不提供任何支付入口。
 * B 方案（移除订单链路）之后，租金和押金都由双方线下自行结算。
 *
 * 【为什么租金有两个字段（post.price 和本表的 rentPrice）】
 * 历史原因：早期用主表 price 表示"日租金"，后来为了支持"15 元/周"这种单位才加了 rentPrice + rentUnit。
 * 现在约定：写了 rentPrice 就以它为准，主表 price 由 resolvePrice 按同一口径同步填一份，
 * 保证列表页和详情页显示的是同一个数。
 *
 * @author 校集后端
 */
// 【来源】第三方库：Lombok。@Data 生成 getter/setter，MP 填值依赖 setter
@Data
// 【来源】第三方库：MyBatis-Plus。绑定表名
@TableName("post_rent")
public class PostRent {

    /** 主键兼关联键，值 = post.id；非自增列，由 saveExt 手动赋值 */
    // 【注解作用】第三方注解，标记主键字段，MP 据此生成 where post_id = ?
    @TableId
    private Long postId;

    /** 租金，配合 rentUnit 理解：15 + "天" 就是 15 元/天。DDL 里 NOT NULL，发布时必须给 */
    private BigDecimal rentPrice;

    /** 计价单位：天 / 周。不填时 saveExt 兜底为"天"，与 DDL 默认值一致 */
    private String rentUnit;

    /** 押金，仅记录给双方看，平台不经手。不填时 saveExt 兜底为 0 */
    private BigDecimal deposit;

    /** 起租天数，如 3 表示至少租 3 天；不填时兜底为 1。用来过滤"只租一天"的无效需求 */
    private Integer minDays;

    /**
     * 可租起始时间。
     * ⚠️ 恒为 NULL：DDL 里有这一列，但 PostCreateReq 没有对应字段、前端也没有控件，
     * saveExt 根本没赋值。要用它得先补 DTO 字段再补页面，光改实体没用。
     */
    private LocalDateTime availableFrom;
}
