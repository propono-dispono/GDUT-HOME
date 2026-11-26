package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 失物招领扩展表 post_lost 的实体，一条帖子最多一行。
 *
 * 谁写：PostServiceImpl 的 saveExt（业务线是 LOST 时插一行）
 * 谁读：PostServiceImpl 的 detail（第 703 行 selectById），列表页走 selectBatchIds（第 562 行）
 *
 * 【失物这条线的特殊性】失物帖不产生任何交易，只能靠私信联系；
 * 捡到的人看到帖子后自己去私信，系统不介入。
 *
 * 【为什么"寻物/拾物"用 Integer 1、2，而不是一个 boolean】
 * 两种情况要显示的三套文案都不同（标题、按钮、提示语），用一个字段装下"哪一类"，
 * 前端一次判断就能决定整套文案；用 boolean 表达不了"还有第三种可能"的扩展空间。
 *
 * @author 校集后端
 */
// 【来源】第三方库：Lombok。@Data 生成 getter/setter/toString/equals，MP 填值需要 setter
@Data
// 【来源】第三方库：MyBatis-Plus。绑定表名，省去 XML 里手写 resultMap
@TableName("post_lost")
public class PostLost {

    /** 主键兼关联键，值 = post.id；不是自增列，由 saveExt 手动赋值 */
    // 【注解作用】第三方注解，标记主键字段，selectById / updateById 靠它拼 where 条件
    @TableId
    private Long postId;

    /** 1 寻物启事（我丢东西了） / 2 拾物招领（我捡到东西了）。DDL 里 NOT NULL，取值只允许 1 或 2 */
    private Integer lostType;

    /** 物品名称，如"黑色雨伞""校园卡"。NOT NULL，Service 层已校验非空 */
    private String itemName;

    /** 丢失 / 拾取地点，如"二饭三楼"。NOT NULL，帮失主判断是不是自己的东西 */
    private String lostPlace;

    /** 丢失 / 拾取时间。DDL 允许 NULL —— 发帖人经常记不清具体时刻，不能强制填 */
    private LocalDateTime lostTime;
}
