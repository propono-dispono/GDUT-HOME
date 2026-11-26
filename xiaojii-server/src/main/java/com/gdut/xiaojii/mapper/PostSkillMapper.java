package com.gdut.xiaojii.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gdut.xiaojii.entity.PostSkill;
import org.apache.ibatis.annotations.Mapper;

/**
 * 技能互助扩展表 post_skill 的数据访问接口。
 *
 * 【为什么接口里是空的】只有 saveExt 写入、detail 按主键读取两种用法，
 * BaseMapper 自带的方法已经覆盖，写自定义 SQL 反而是负担。
 *
 * @author 校集后端
 */
// 【来源】第三方库：MyBatis 的注解。启动时为接口生成代理实现类并注册成 Bean，否则注入失败
@Mapper
// 【来源】第三方库：MyBatis-Plus 的 BaseMapper，泛型指定实体以确定操作哪张表
public interface PostSkillMapper extends BaseMapper<PostSkill> {
}
