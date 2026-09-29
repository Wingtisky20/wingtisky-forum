package com.wingtisky.forum.forum.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户-角色关联。
 *
 * <p>单独一个 Mapper 而不是并进 UserMapper：这两张表的写入时机不同——
 * 用户是注册时建，角色关联是注册流程的第二步（也可能将来被管理员单独调整）。
 * 合在一起会让 {@code UserMapper} 同时承担两种职责，改动时更容易互相牵连。
 */
@Mapper
public interface UserRoleMapper {

    int insert(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /** 该用户有几个角色。用于验证注册流程确实分配了默认角色。 */
    int countByUserId(Long userId);
}
