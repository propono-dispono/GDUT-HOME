package com.gdut.xiaojii.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gdut.xiaojii.entity.PostGroupbuy;
import org.apache.ibatis.annotations.Mapper;

/**
 * post_groupbuy 的数据访问接口。
 *
 * 接口为空是有意的：连"人数 +1"这种核心操作也只是带条件的 update，
 * BaseMapper 的 update(entity, wrapper) 就能表达，不必写 XML。业务在 PostServiceImpl。
 *
 * @author 校集后端
 */
// 【来源】第三方库：MyBatis 的注解。启动时生成代理实现类注册成 Bean，去掉它注入失败
@Mapper
// 【来源】第三方库：MyBatis-Plus 的 BaseMapper，泛型指定实体以确定操作哪张表
public interface PostGroupbuyMapper extends BaseMapper<PostGroupbuy> {
}
