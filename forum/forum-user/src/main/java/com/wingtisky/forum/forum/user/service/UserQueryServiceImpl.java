package com.wingtisky.forum.forum.user.service;

import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.domain.user.UserQueryService;
import com.wingtisky.forum.forum.user.entity.User;
import com.wingtisky.forum.forum.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link UserQueryService} 的实现——跨域契约在**用户域这一侧**的落地。
 *
 * <p>它本身没有业务规则，只做三件事：短路掉空输入、调 mapper、把 {@code User} 转成
 * {@link UserBrief}。**转换放在这里而不是 mapper 里**，是为了让 SQL 保持最朴素的写法
 * （record 没有无参构造，MyBatis 映射它要写 {@code <constructor>} resultMap），
 * 同时让"投影怎么映射"这件事变成一段可以单测的纯函数。
 *
 * <p><b>为什么它不去实现任何缓存</b>：M3 的亮点 1 会在这里加缓存
 * （用户信息读多写少、"改昵称"是明确的失效事件）。**现在不加**——
 * 契约已经定好，M3 只需改实现，调用方一行不用动，这正是把契约单独分出来换来的。
 */
@Service
public class UserQueryServiceImpl implements UserQueryService {

    private final UserMapper userMapper;

    public UserQueryServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Map<Long, UserBrief> findBriefs(Collection<Long> userIds) {
        // 空集合必须在这里短路：传下去会拼出 `IN ()` 这种非法 SQL，
        // 而它只在"这一页恰好一个作者都没有"时才会炸——正是最容易漏测的那一类。
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        return userMapper.selectBriefsByIds(userIds).stream()
                // id 是主键，不会重复，所以 toMap 不会抛重复键异常；
                // 不补默认值，因为查不到的 id 本来就不该出现在结果里（契约里写了）
                .collect(Collectors.toMap(User::getId, UserQueryServiceImpl::toBrief));
    }

    @Override
    public Optional<UserBrief> findBrief(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }

        // **复用批量方法，而不是另写一条单条 SQL。**
        // 两条 SQL 会各自演化，迟早出现"一条带 deleted = 0、另一条忘了"这种不一致，
        // 而它的表现是"详情页能看见已注销用户、列表页看不见"——极难排查。
        // 单条查询走一次 IN 的代价可以忽略，一致性比那点开销值钱。
        return Optional.ofNullable(findBriefs(List.of(userId)).get(userId));
    }

    /** 投影转换。纯函数，不碰任何外部状态——所以它值得单独测。 */
    private static UserBrief toBrief(User user) {
        return new UserBrief(user.getId(), user.getNickname(), user.getAvatar());
    }
}
