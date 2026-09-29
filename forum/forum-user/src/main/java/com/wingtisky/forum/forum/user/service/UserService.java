package com.wingtisky.forum.forum.user.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.forum.user.entity.Role;
import com.wingtisky.forum.forum.user.entity.RoleCode;
import com.wingtisky.forum.forum.user.entity.User;
import com.wingtisky.forum.forum.user.mapper.RoleMapper;
import com.wingtisky.forum.forum.user.mapper.UserMapper;
import com.wingtisky.forum.forum.user.mapper.UserRoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 用户领域服务：注册、登录校验、资料读写。
 *
 * <p><b>本类只做领域逻辑，不碰 HTTP</b>——参数的格式校验（长度、非空、正则）由
 * Controller 层的 DTO 注解负责，这里假定入参已通过校验。这样 Service 才能被
 * 非 HTTP 的入口（定时任务、消息消费者）复用。
 *
 * <p><b>返回的是实体，不是 DTO</b>：实体属于领域层，DTO 是 API 契约、属于适配层。
 * 让 Service 返回 DTO 会把"接口返回什么形状"这件事绑死在领域层里。
 * 代价是 Controller 必须记得转换——Task 7 用一条"响应 JSON 里不含 password 字段"
 * 的测试把它钉住，而不是靠自觉。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /**
     * 用于抹平"用户不存在"与"密码错误"的**响应时间差**。
     *
     * <p>BCrypt 比对是刻意慢的（约 100 毫秒）。若用户不存在时直接返回、跳过比对，
     * 那么"不存在的用户名"会快得多——攻击者不需要看懂任何错误码，
     * **掐表就能枚举出哪些用户名真实存在**。
     *
     * <p>所以这里准备一个真实的 BCrypt 哈希，用户不存在时拿它去比：
     * 结果必然是 false，但耗时与真实比对一致。
     *
     * <p>用随机串在类加载时生成，而不是硬编码一个字面量——硬编码的值容易被
     * 误认为"某个真实密码的哈希"，也容易在迁移中被当成测试数据删掉。
     */
    private static final String TIMING_EQUALIZER_HASH =
            new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper,
                       RoleMapper roleMapper,
                       UserRoleMapper userRoleMapper,
                       PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 注册。成功后返回新用户（{@code password} 字段是哈希，调用方不得外传）。
     *
     * <p><b>唯一性靠数据库唯一索引，不是"先查再插"。</b>后者在并发下有窗口：
     * 两个请求同时查到"没被占用"，然后都插入成功。捕获 {@link DuplicateKeyException}
     * 是唯一可靠的做法——把判断交给数据库，因为只有它持有真正的锁。
     */
    @Transactional
    public User register(String username, String rawPassword, String nickname) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setNickname(nickname);
        user.setStatus(User.STATUS_ACTIVE);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 这里只记 debug：用户重复注册是正常的业务失败，不是系统故障。
            // 记成 warn/error 会让日志淹没在正常流量里。
            log.debug("用户名已存在: {}", username);
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }

        Role defaultRole = roleMapper.selectByCode(RoleCode.USER.name());
        if (defaultRole == null) {
            // 这是**部署问题**（建表脚本里的角色初始化没跑），不是用户输入问题。
            // 细节只进日志，不进响应——否则等于把内部表结构告诉调用方。
            log.error("默认角色 {} 未在 t_role 表中初始化，注册无法完成", RoleCode.USER);
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
        userRoleMapper.insert(user.getId(), defaultRole.getId());

        log.info("新用户注册: id={}, username={}", user.getId(), username);
        return user;
    }

    /**
     * 校验用户名与密码。成功返回用户，失败抛业务异常。
     *
     * <p><b>两条防用户名枚举的措施，都在这里</b>：
     * <ol>
     *   <li>「用户不存在」与「密码错误」返回**同一个错误码**，调用方无法从文案区分</li>
     *   <li>用户不存在时**照样做一次 BCrypt 比对**，抹平响应时间差——
     *       否则光靠计时就能把上一条绕过</li>
     * </ol>
     *
     * <p><b>顺序上先验密码、再看账号状态</b>：若先判断状态，那么"账号已被禁用"
     * 这个响应就等于告诉攻击者"这个用户名存在"。先验密码则只要密码不对，
     * 返回的永远是同一个结果。
     */
    public User authenticate(String username, String rawPassword) {
        User user = userMapper.selectByUsername(username);

        String hashToCompare = (user == null) ? TIMING_EQUALIZER_HASH : user.getPassword();
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToCompare);

        if (user == null || !passwordMatches) {
            log.debug("登录失败: username={}", username);
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        if (!user.isActive()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        return user;
    }

    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    /** 用户的角色码。签发 JWT 时写进载荷，避免每个请求都查库。 */
    public List<String> getRoleCodes(Long userId) {
        return userMapper.selectRoleCodesByUserId(userId);
    }

    /**
     * 修改可变资料。
     *
     * <p><b>不在这里做归属校验</b>：那是"调用者有没有资格改这个 id"的问题，
     * 属于鉴权层（Task 5 的 {@code @PreAuthorize("@authz.isOwner(#id)")}）。
     * 混进 Service 的后果是校验散落在每个方法里，而漏掉一个不会有任何报错。
     */
    @Transactional
    public void updateProfile(Long userId, String nickname, String avatar, String email) {
        if (nickname == null && avatar == null && email == null) {
            // 不让它走到 SQL：全部为 null 时动态 SQL 会生成空的 SET 子句而语法错误，
            // 那个报错对排查毫无帮助
            throw new BizException(ErrorCode.PARAM_INVALID, "至少提供一个要修改的字段");
        }
        User patch = new User();
        patch.setId(userId);
        patch.setNickname(nickname);
        patch.setAvatar(avatar);
        patch.setEmail(email);
        userMapper.updateProfile(patch);
    }
}
