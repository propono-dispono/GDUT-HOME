package com.gdut.xiaojii.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gdut.xiaojii.entity.GroupbuyMember;
import org.apache.ibatis.annotations.Mapper;

/** 拼单成员 groupbuy_member 的 Mapper；接口为空，成员状态切换全是带条件的 update */
@Mapper
public interface GroupbuyMemberMapper extends BaseMapper<GroupbuyMember> {
}
