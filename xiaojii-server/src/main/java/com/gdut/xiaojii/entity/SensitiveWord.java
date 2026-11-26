package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 敏感词（对应表 sensitive_word，DDL 见 sql/schema.sql:326）。
 *
 * 【它在链路里的位置】
 * 一行 = 一个词条。ContentCheckService 每次发帖时取出所有 enabled = 1 的词条，
 * 逐条与"标题 + 正文 + 标签"拼成的文本比对，命中就抛 1095 拒绝发布。
 * 也就是说：这张表的内容直接决定"什么内容发不出去"，改词条等于改业务规则。
 *
 * 【表结构与本类的差异】
 * 表里还有 created_at（录入时间）列，本类故意没有映射 ——
 * 目前后台不需要按录入时间排序。将来要加，补一个 private LocalDateTime createdAt 即可，
 * 插入时靠数据库 DEFAULT CURRENT_TIMESTAMP 兜底，不用手动赋值。
 *
 * @author 校集后端
 */
// 【注解作用】@Data（Lombok）：生成 getter/setter/toString/equals/hashCode。
//   这里必须有 setter —— MyBatis 查库后靠 setter 把列值写进字段。
//   不加会怎样：selectList 返回的对象字段全是 null（不报错，只是查不到值）
@Data
// 【注解作用】@TableName（MyBatis-Plus）：声明对应的表名。
//   不加也会按类名推断（SensitiveWord → sensitive_word，碰巧一致）；
//   显式写出来是为了将来改表名时不用猜"是约定生效还是代码生效"。
@TableName("sensitive_word")
public class SensitiveWord {

    // 【注解作用】@TableId(type = IdType.AUTO)：标记主键，并声明"由数据库自增生成"。
    //   不加 type 时 MP 默认按雪花算法生成 Long，与 DDL 的 AUTO_INCREMENT 对不上，
    //   插入后拿不到真实 id（本表其实只读不写，影响不大，但保持一致更稳）
    @TableId(type = IdType.AUTO)
    private Long id;

    // 字段：word —— 词条本身
    // 类型/约束：VARCHAR(64)，唯一索引 uk_word（同一个词不能录两次）
    // 取值：两种写法
    //   ① 普通词 "代写"        —— 文本里出现这几个字就命中
    //   ② 组合词 "代取+外卖"    —— 用 + 切分，**每段都出现**才算命中
    // 作用：ContentCheckService 拿它逐条与用户文本比对
    // 【为什么需要组合词】单写 "外卖" 会误杀合法的跑腿需求（帮人取餐），
    //   组合成 "代取+外卖" 才能精确表达"代取外卖上门"这个违规场景。
    //   这是"用词的组合表达语义"的土办法，够用但不好扩展
    // 【约定】库里统一存**小写**（如 "weixin"），因为比对前会把用户文本整体 toLowerCase()
    //   如果词条存了大写，永远匹配不上（这种 bug 很难发现，因为字面上看着是对的）
    private String word;

    // 字段：category —— 分类
    // 类型/约束：VARCHAR(32) NOT NULL DEFAULT 'OTHER'
    // 取值：禁售 / 违禁 / 辱骂 / 其他
    // 作用：只给后台管理页面分组展示用
    // 【重要】代码逻辑完全不读它 —— 真正影响行为的只有 level 和 enabled。
    //   改分类不会改变拦截结果，只是让管理员好找词
    private String category;

    // 字段：level —— 级别，决定"命中后怎么处理"
    // 类型/约束：TINYINT DEFAULT 1
    // 取值：
    //   1 —— 任何业务线命中都直接拒绝（返回 1095）。例："代写"、"代考"，这类词没有任何合法场景
    //   2 —— **仅拼单（GROUPBUY）拒绝，跑腿放行**。例："外卖"：
    //         "帮取外卖"是合法跑腿，但"拼外卖到宿舍"有食品安全风险，必须拦
    // 作用：ContentCheckService 据此决定拦不拦
    // 【注意它不是"严重程度"而是"处理方式"】所以同一个词在不同业务线下结论相反，
    //   判定逻辑在 ContentCheckService.check(text, bizType) 里，不在这个类里
    private Integer level;

    // 字段：enabled —— 是否启用
    // 类型/约束：TINYINT DEFAULT 1
    // 取值：1 启用 / 0 停用
    // 作用：ContentCheckService 只查 enabled = 1 的词条（WHERE enabled = 1）
    // 【这是软开关】管理员要临时下线某个词，是把它置 0，而不是 DELETE：
    //   词条还在，随时能恢复；也不会破坏词库的完整性
    // 【为什么用 Integer 而不是 boolean】MySQL 的 tinyint 与 boolean 在不同驱动/版本下
    //   映射行为不一致；存 0/1 整数最稳，SQL 里也能直接比较（WHERE enabled = 1）
    private Integer enabled;
}
