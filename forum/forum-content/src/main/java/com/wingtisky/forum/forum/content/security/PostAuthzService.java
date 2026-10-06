package com.wingtisky.forum.forum.content.security;

import com.wingtisky.forum.common.security.CurrentUser;
import com.wingtisky.forum.forum.content.entity.Post;
import com.wingtisky.forum.forum.content.mapper.PostMapper;
import org.springframework.stereotype.Component;

/**
 * 帖子资源的归属校验。Bean 名显式定为 {@code postAuthz}，
 * 用法与用户域的 {@code @authz} 一致：{@code @PreAuthorize("@postAuthz.isOwner(#id)")}。
 *
 * <h2>为什么不能复用用户域的 {@code AuthzService}</h2>
 *
 * 用户域的 {@code @authz.isOwner(#id)} 比的是**"这个用户 ID 是不是我"**。
 * 而帖子接口里的 {@code #id} 是**帖子 ID**。直接照抄的话，比的是
 * "帖子 ID 是否等于我的用户 ID"，后果是：
 *
 * <ul>
 *   <li>几乎永远为假 → <b>作者改自己的帖子也会被拒</b>（一眼能发现的坏）</li>
 *   <li>更麻烦：某个用户的 ID 恰好等于某个帖子的 ID 时，<b>会错误放行</b>——
 *       这个不会报错，只会在特定数据下悄悄发生</li>
 * </ul>
 *
 * <h2>为什么不把它塞进用户域的 AuthzService</h2>
 *
 * 那需要用户域去读帖子表——也就是让 {@code forum-user} 依赖 {@code forum-content}，
 * 直接踩碎"三个业务域互不依赖"这条边界（由 {@code ArchitectureTest} 规则 2 强制）。
 * **判断"这个东西是不是你的"，必须由拥有这个数据的域来做。**
 *
 * @see com.wingtisky.forum.forum.user.security.AuthzService 用户域的对应实现
 */
@Component("postAuthz")
public class PostAuthzService {

    private final PostMapper postMapper;

    public PostAuthzService(PostMapper postMapper) {
        this.postMapper = postMapper;
    }

    /**
     * 当前登录用户是不是这条帖子的作者。
     *
     * <p><b>帖子不存在或已被删除时返回 {@code false}</b>（而不是抛异常）：
     * 本方法会被 SpEL 调用，保持"只回答是或否"最简单。至于调用方最终收到的是
     * 403 还是 404，由授权规则决定——这里返回 false 意味着"对不存在的帖子，
     * 你连'是不是你的'都谈不上"，返回 403 是合理的。
     *
     * <p><b>代价</b>：每次校验都要查一次帖子。这是"鉴权必须比对真实数据"的必然开销，
     * M3 加缓存后这次查询会命中缓存。
     */
    public boolean isOwner(Long postId) {
        Long currentUserId = CurrentUser.id();
        if (currentUserId == null || postId == null) {
            return false;
        }
        Post post = postMapper.selectById(postId);
        // post 为 null 说明帖子不存在或已删除——此时不认为是"你的"
        return post != null && currentUserId.equals(post.getAuthorId());
    }
}
