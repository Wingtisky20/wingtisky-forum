package com.wingtisky.forum.forum.user.mapper;

import com.wingtisky.forum.forum.user.entity.Role;
import org.apache.ibatis.annotations.Mapper;

/** 角色表的读取。本项目角色只有三个且由建表脚本初始化，因此不需要写操作。 */
@Mapper
public interface RoleMapper {

    /** 按角色码查。注册时用它取"默认角色"的 ID。 */
    Role selectByCode(String code);
}
