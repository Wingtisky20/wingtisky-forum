package com.wingtisky.forum.forum.user.mapper;

import com.wingtisky.forum.forum.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 用户表的读写。
 *
 * <p><b>SQL 写在 XML 里而不是用注解</b>：M6 的亮点是深翻页优化（Deferred Join），
 * 那需要能自由地写和调整 SQL，并配 {@code EXPLAIN} 做前后对比。用注解拼 SQL
 * 在复杂查询上会很快变得难以阅读，而现在养成手写 XML 的习惯，M6 就不必临时改风格。
 *
 * <p>每个查询都带 {@code deleted = 0}——逻辑删除的过滤条件散在被遗忘的地方
 * 是这类设计最典型的 bug，"已经删掉的用户还能登录"就是它。
 */
@Mapper
public interface UserMapper {

    /**
     * 插入用户。主键回填到 {@code user.id}（XML 里配了 useGeneratedKeys）。
     *
     * <p><b>调用方必须处理 {@code DuplicateKeyException}</b>：用户名的唯一性由
     * 数据库唯一索引保证，不是"先查再插"——后者在并发下有窗口，
     * 两个请求都查到"没被占用"，然后都插入成功。
     */
    int insert(User user);

    User selectById(Long id);

    User selectByUsername(String username);

    /**
     * 更新可变资料。只更新非 null 字段。
     *
     * <p><b>调用方必须保证至少一个字段非 null</b>：全部为 null 时动态 SQL 生成的是
     * 空 SET 子句，会直接语法错误。Service 层已做校验。
     */
    int updateProfile(User user);

    /** 该用户拥有的角色码。登录签发令牌时要用（角色写进 JWT 载荷）。 */
    List<String> selectRoleCodesByUserId(Long userId);
}
