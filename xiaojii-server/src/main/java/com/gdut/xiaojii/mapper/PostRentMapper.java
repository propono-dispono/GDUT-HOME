package com.gdut.xiaojii.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gdut.xiaojii.entity.PostRent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 物品租赁扩展表 post_rent 的数据访问接口。
 *
 * 【为什么接口里是空的】租赁帖只做"按主键查一条""插入一条"，
 * 没有任何聚合或跨表查询，BaseMapper 自带方法足够。
 *
 * @author 校集后端
 */
// 【来源】第三方库：MyBatis 的注解。启动时生成代理实现类注册成 Bean，去掉它 inject 直接失败
@Mapper
// 【来源】第三方库：MyBatis-Plus 的 BaseMapper，泛型指定实体以确定操作哪张表
public interface PostRentMapper extends BaseMapper<PostRent> {
}
