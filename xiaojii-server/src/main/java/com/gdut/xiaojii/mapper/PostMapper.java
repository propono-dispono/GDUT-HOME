package com.gdut.xiaojii.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gdut.xiaojii.entity.Post;
import org.apache.ibatis.annotations.Mapper;

/**
 * 帖子主表 post 的数据访问接口。
 *
 * 【为什么没有 XML】信息流的筛选条件全部能用 LambdaQueryWrapper 表达
 * （等值 + LIKE + 区间 + 排序），BaseMapper 的 selectPage / selectList 就够；
 * 将来要加"按发布人信用分排序"这类跨表排序时，再补 XML 和方法。
 *
 * 【谁能用】插入在 PostServiceImpl.create，查询在 feed / mine / detail。
 * 注意：删帖是逻辑删除（post.deleted），不是物理删除，所以 deleteById 实际是 update。
 *
 * @author 校集后端
 */
// 【来源】第三方库：MyBatis 的注解。启动时为接口生成代理实现类并注册成 Bean，去掉了注入直接失败
@Mapper
// 【来源】第三方库：MyBatis-Plus 的 BaseMapper，泛型指定 Post 实体，按实体上的 @TableName 找表
public interface PostMapper extends BaseMapper<Post> {
}
