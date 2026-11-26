package com.gdut.xiaojii.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gdut.xiaojii.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper。
 *
 * 【它在登录链路中的位置】
 * 第 6 站（数据访问）。AuthServiceImpl.doLogin 通过它查用户、插用户。
 *
 * 【为什么这个文件只有一行方法体都没有，却能用】
 * 继承 BaseMapper<User> 后，MyBatis-Plus 在启动时用 JDK 动态代理为它生成实现类，
 * 自动提供 insert / updateById / selectById / selectList / selectCount / selectBatchIds 等
 * 十余个单表方法。调用时不需要任何 SQL —— SQL 由 MP 根据实体上的 @TableName / @TableId 生成。
 *
 * 【本项目的一个事实】
 * 21 个 Mapper 全部是这种空壳：没有 XML、没有 @Select 注解方法。
 * 复杂查询是用 LambdaQueryWrapper 在 Service 层拼的，不是写在 Mapper 里。
 *
 * @author 校集后端
 */
// 【注解作用】@Mapper：告诉 MyBatis "这是一个 Mapper 接口"，由它生成代理实现。
//   但本项目的启动类上还写了 @MapperScan("com.gdut.xiaojii.mapper")，
//   作用重复 —— 有 @MapperScan 时这个注解其实可以省略。
//   两处都留属于"双保险"，不会出错，只是略冗余。
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
// ┌─ 【面试考点 1】Mapper 是接口、没有实现类，调用时怎么执行的？ ──────────────
// │ Q：userMapper.selectOne(...) 明明没有实现方法，为什么能跑？
// │ A：MyBatis 用 JDK 动态代理（Proxy + InvocationHandler）为每个 Mapper 接口生成实现对象：
// │    调用被 MapperProxy 拦截 → 根据"接口全名 + 方法名"找到对应的 MappedStatement
// │    （MP 是启动时按 BaseMapper 的方法签名动态注册的）→ 拼出 SQL → 交给 JDBC 执行 → 结果映射成实体。
// │    所以接口方法名不是随便起的，它和 MappedStatement 的 id 绑定。
// └────────────────────────────────────────────────────
// ┌─ 【面试考点 2】BaseMapper 提供了哪些方法？为什么不手写 SQL？ ──────────────
// │ Q：用 BaseMapper 和自己写 SQL 各有什么取舍？
// │ A：BaseMapper 开箱提供单表 CRUD + 条件构造器（Wrapper），零 SQL、防注入、改字段不用改 SQL；
// │    代价是"多表 join、复杂聚合"它表达不出来 —— 本项目这类需求都放在 Service 里
// │    用多次查询 + 内存组装解决（这也正是 assembleItems 里 N+1 优化的由来）。
// │    单表 CRUD 用 BaseMapper、复杂查询再考虑 XML/注解 SQL，是常见分工。
// └────────────────────────────────────────────────────
