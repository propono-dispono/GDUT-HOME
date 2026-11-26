package com.gdut.xiaojii.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gdut.xiaojii.entity.PostLost;
import org.apache.ibatis.annotations.Mapper;

/**
 * 失物招领扩展表 post_lost 的数据访问接口。
 *
 * 【为什么接口里是空的】继承 BaseMapper 已经够用：写入只有 saveExt 的 insert，
 * 读取只有 detail 的 selectById 和列表页的 selectBatchIds，没有自定义 SQL。
 * 哪天要"按丢失时间范围筛选"再回来加方法。
 *
 * @author 校集后端
 */
// 【来源】第三方库：MyBatis 的注解。启动时让 MyBatis 为接口生成代理实现类并注册成 Bean；
// 去掉它，PostServiceImpl 里的 lostMapper 注入会失败，应用起不来。
@Mapper
// 【来源】第三方库：MyBatis-Plus 的 BaseMapper，泛型指定实体，它就按实体上的 @TableName 找表
public interface PostLostMapper extends BaseMapper<PostLost> {
}
