package com.gdut.xiaojii.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gdut.xiaojii.entity.PostErrand;
import org.apache.ibatis.annotations.Mapper;

/**
 * 跑腿扩展表 post_errand 的数据访问接口。
 *
 * 【为什么接口里是空的】继承 BaseMapper 后已经拿到 insert / selectById / selectBatchIds /
 * deleteById 等十几个常用方法，而这张表只按主键读写：
 * 写入在 PostServiceImpl 的 saveExt（insert），读取在 detail（selectById）。
 * 将来若出现"按截止时间查超时跑腿"这类跨字段查询，再在这里加方法并配 XML。
 *
 * @author 校集后端
 */
// 【来源】第三方库：MyBatis 的注解。启动时让 MyBatis 为这个接口生成代理实现类并注册成 Bean；
// 不加它，PostServiceImpl 里注入 errandMapper 会启动失败，报找不到对应类型的 Bean。
@Mapper
// 【来源】第三方库：MyBatis-Plus 的 BaseMapper，泛型写实体类，它就知道该操作哪张表（靠实体上的 @TableName）
public interface PostErrandMapper extends BaseMapper<PostErrand> {
}
