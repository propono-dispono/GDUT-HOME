package com.gdut.xiaojii.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gdut.xiaojii.entity.ErrandTake;
import org.apache.ibatis.annotations.Mapper;

/**
 * 跑腿接单记录表 errand_take 的数据访问接口。
 *
 * 【为什么接口里是空的】两张表的所有写操作都能用 BaseMapper + LambdaUpdateWrapper 表达：
 * 接单（insert）、状态跃迁（update ... where status = 期望值）都是单表条件更新，
 * 不需要手写 XML。
 *
 * 【为什么不在这里写"取当前进行中记录"的自定义方法】
 * `selectOne(wrapper.eq(postId).isNull(activePostId) 的反面)` 这一类查询用 Wrapper 已经够短，
 * 加自定义方法反而多一处要和 XML 同步维护的地方。
 * ⚠️ 但要注意：selectOne 遇到多行会抛 TooManyResultsException ——
 * 正因如此才需要表上的 uk_active_post 兜底，保证"进行中"永远只有一行。
 *
 * @author 校集后端
 */
// 【来源】第三方库：MyBatis 注解。让启动时为该接口生成代理实现并注册成 Bean
@Mapper
// 【来源】第三方库：MyBatis-Plus 的 BaseMapper，泛型写实体类即可（表名来自 @TableName）
public interface ErrandTakeMapper extends BaseMapper<ErrandTake> {
}
