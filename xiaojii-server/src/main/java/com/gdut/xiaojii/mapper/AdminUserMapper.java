package com.gdut.xiaojii.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gdut.xiaojii.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理员 Mapper。单表 CRUD 走 BaseMapper，无需手写 SQL。
 *
 * @author 校集后端
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {
}
