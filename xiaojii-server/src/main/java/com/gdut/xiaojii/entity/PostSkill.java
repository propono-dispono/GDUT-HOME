package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 技能互助扩展表 post_skill 的实体，一条帖子最多一行。
 *
 * 谁写：PostServiceImpl 的 saveExt（业务线是 SKILL 时插一行）
 * 谁读：PostServiceImpl 的 detail（第 712 行 selectById）拼详情
 *
 * ⚠️ 这张表目前有两个"占位字段"：serviceMode 和 durationDesc。
 * 前端发布页还没有对应的录入控件，saveExt 里直接写死成"线上"和空串，
 * 所以库里所有技能帖这两个字段都长一样。等要按"线上/线下"筛选时必须先补录入入口。
 *
 * @author 校集后端
 */
// 【来源】第三方库：Lombok。@Data 生成 getter/setter，MP 查询后要靠 setter 填值
@Data
// 【来源】第三方库：MyBatis-Plus。绑定表名
@TableName("post_skill")
public class PostSkill {

    /** 主键兼关联键，值 = post.id；非自增列，由 saveExt 手动赋值 */
    // 【注解作用】第三方注解，标记主键字段，MP 据此生成 where post_id = ?
    @TableId
    private Long postId;

    /** 技能类目，如"代做 PPT""修电脑""辅导高数"。NOT NULL，Service 层已校验 */
    private String serviceType;

    /** 预计交付天数，不填时 saveExt 兜底为 1；给需求方一个等待预期 */
    private Integer deliveryDays;

    /** 服务方式：线上 / 线下。⚠️ 目前恒为"线上"（硬编码，前端无录入入口） */
    private String serviceMode;

    /** 单次时长描述，如"2 小时"。⚠️ 目前恒为空串（同上，属于占位字段） */
    private String durationDesc;
}
