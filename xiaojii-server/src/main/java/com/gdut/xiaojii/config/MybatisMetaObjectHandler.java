package com.gdut.xiaojii.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充。
 *
 * 【这个类是干什么的】
 * 它是 MyBatis-Plus 提供的一个回调：每次执行 insert / update 之前，
 * MP 会把即将入库的实体交给它，让它把创建时间、更新时间填进去。
 * 业务代码因此不需要再写 setCreatedAt(...)。
 *
 * 【为什么需要它】
 * 没有这个处理器时，createdAt 全靠 Service 手动 set，漏一个字段就会插入 null，
 * 而 DDL 里 created_at 是 NOT NULL，直接报错。
 *
 * 【使用前提】
 * 实体字段上要有 @TableField(fill = FieldFill.INSERT) 或 INSERT_UPDATE，否则不会触发。
 *
 * 【它在整体中的位置】
 * 属于 MyBatis 层的横切能力，对 Service 透明。
 * 和 MybatisPlusConfig 是同一层次：一个管 SQL 改写，一个管字段填充。
 *
 * 【生效范围（基于当前代码实测）】
 * 21 个实体里只有 14 个真正接了自动填充：
 *   User、UserProfile、UserVerify、AdminUser、Post、GroupbuyMember、Favorite、
 *   ChatSession、ChatMessage、Notice、Announcement、Report、AuditRecord、Review。
 * 另外两类不归它管：
 *   - 6 个帖子子表（PostErrand/PostLost/PostSkill/PostRent/PostGroupbuy）没有时间字段；
 *   - EduCredential 有时间字段但没标 fill，靠 DDL 的 DEFAULT CURRENT_TIMESTAMP /
 *     ON UPDATE CURRENT_TIMESTAMP 兜底。
 *
 * @author 校集后端
 */
// 【注解作用】@Component：把这个类注册成 Spring Bean。
//   MyBatis-Plus 启动时会去容器里找 MetaObjectHandler 的实现类；找不到就一直不填充，
//   而且不会报错 —— 所以这个注解漏了极难排查。
@Component
public class MybatisMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时自动填充。
     *
     * 参数：metaObject —— 即将插入的实体的反射包装。
     *   它相当于"实体对象的一把遥控器"：可以按字段名读值、写值，
     *   也能判断字段是否存在。
     *
     * 触发时机：调用 mapper.insert(entity) 时，MP 在生成 INSERT 语句之前回调这里。
     *
     * 边界与易错点：
     *   ① 字段名必须是实体的真实字段名。本项目是 createdAt / updatedAt，
     *      不是 createTime / updateTime。写错名字不会报错，只是永远填不进去。
     *   ② 实体上没写 @TableField(fill=...) 时，MP 不会调用这里 ——
     *      所以"处理器写了但没生效"通常是实体漏注解，而不是这里写错。
     *   ③ 实体如果压根没有这个字段，strictInsertFill 会因 hasSetter 为 false
     *      而静默跳过，不会抛异常。
     */
    // ┌─ 【面试考点 1】strictInsertFill 和 setFieldValByName 有什么区别？ ─────
    // │ Q：填充字段用哪个方法？为什么？
    // │ A：setFieldValByName 会无条件覆盖；strictInsertFill 只在"字段有 setter
    // │    且当前值为 null"时才写。
    // │    业务上必须用 strict 版本：调用方可能故意传了一个历史时间
    // │    （数据订正、导入历史数据、单测构造过去的时间），无条件覆盖会把这些值冲掉，
    // │    表现为"我明明设置了，插入后却变了"。
    // └────────────────────────────────────────────────────
    @Override
    /** 新增fill */
    public void insertFill(MetaObject metaObject) {
        // 只取一次当前时间，保证同一次插入里 createdAt 与 updatedAt 完全相等。
        // 如果两次各调一次 now()，极端情况下会差几毫秒，导致
        // "创建时间晚于更新时间"这种看起来很奇怪的数据。
        LocalDateTime now = LocalDateTime.now();

        // strictInsertFill：只在字段为 null 时填充，不覆盖调用方显式设置的值
        // （例如定时任务要写一个"过去的时间"做测试，不能被覆盖）
        //
        // 四个参数的含义（这个方法有重载，这里是最常用的四参版本）：
        //   ① metaObject            —— 实体反射包装
        //   ② "createdAt"           —— 要填的字段名
        //   ③ LocalDateTime.class   —— 字段类型，MP 用它做类型匹配
        //   ④ now                   —— 要填的值
        //
        // "strict" 的语义：字段必须存在 setter、且当前值为 null，才写入。
        //   对比 setFieldValByName(...)（无 strict 前缀）：它会无条件覆盖 ——
        //   那样会把业务代码手动设置的时间冲掉，所以这里必须用 strict 版本。
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    /**
     * 更新时自动填充。
     *
     * 参数：同上，metaObject 是即将更新的实体（或包装对象）。
     *
     * 触发时机：mapper.updateById(entity) 或 mapper.update(entity, wrapper) 时，
     *   在生成 UPDATE 语句之前。
     *
     * 和 insertFill 的差别：
     *   插入时两个时间都要写（第一次落库）；
     *   更新时只写 updatedAt，绝不能碰 createdAt ——
     *   否则"创建时间"会被改成最后一次修改时间，数据就失去意义了。
     *
     * 注意：如果用 LambdaUpdateWrapper 的 set(...) 直接改某几个字段，
     *   走的是 SQL 层面的更新，不会经过这里（MP 没拿到完整实体）。
     *   那种写法要么依靠 DDL 的 ON UPDATE CURRENT_TIMESTAMP，
     *   要么在 wrapper 里手动 set(Entity::getUpdatedAt, now)。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        // update 时 updated_at 由数据库 ON UPDATE CURRENT_TIMESTAMP 兜底，
        // 这里显式再填一次，方便那些 DDL 没写 ON UPDATE 的表保持一致
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }

    // ┌─ 【面试考点 2】自动填充"不生效"该按什么顺序排查？ ──────────────────
    // │ Q：我在实体里加了 createdAt，也写了 MetaObjectHandler，为什么插入后还是 null？
    // │ A：按这个顺序查（前三个都是"不报错的静默失败"）：
    // │    ① 实体字段有没有 @TableField(fill = FieldFill.INSERT)？
    // │       —— 没有则 MP 根本不会回调处理器；
    // │    ② 处理器有没有注册成 Bean（@Component）？
    // │       —— 没有则容器里找不到实现；
    // │    ③ 字段名对不对？本项目是 createdAt / updatedAt，不是 createTime / updateTime，
    // │       名字对不上不会报错，只是永远填不进去；
    // │    ④ 是不是用的 LambdaUpdateWrapper.set(...) 直接改字段？
    // │       —— 那种写法不经过处理器，需手动 set 或依赖 DDL 的 ON UPDATE CURRENT_TIMESTAMP。
    // │    对方真正想听的是：你知道这几个坑"都不报错"，所以要用断点或打印字段值定位，
    // │    而不是盯着异常日志找。
    // └──────────────────────────────────────────────────────
}
