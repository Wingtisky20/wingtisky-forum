package com.wingtisky.forum.forum.content.security;

import com.wingtisky.forum.common.security.CurrentUser;
import com.wingtisky.forum.forum.content.entity.Comment;
import com.wingtisky.forum.forum.content.mapper.CommentMapper;
import org.springframework.stereotype.Component;

/**
 * 评论的归属校验。Bean 名 {@code commentAuthz}。
 *
 * <p>与帖子那边的 {@link PostAuthzService}（{@code postAuthz}）是同一个套路，
 * 只是查的表不同。**为什么不合并成一个 Bean**：两种资源的归属判断虽然形状像，
 * 但查的是不同的表、不同的类。分开之后，权限注解
 * （{@code @commentAuthz.isOwner(#id)}）自带资源信息，
 * 读代码时不用回到 Bean 里看它到底比的是什么。
 *
 * <p>真出现第三种资源（比如 M7 的订单）时再考虑合并——
 * 那时改名一次的成本，和现在改名差不多，而现在改还要动刚写完的文档与讲解。
 */
@Component("commentAuthz")
public class CommentAuthzService {

    private final CommentMapper commentMapper;

    public CommentAuthzService(CommentMapper commentMapper) {
        this.commentMapper = commentMapper;
    }

    /**
     * 当前登录用户是不是这条评论的作者。评论不存在或已删除时返回 {@code false}。
     *
     * <p><b>⚠️ 入参必须是「评论 ID」。</b>它与 {@code postAuthz.isOwner(帖子ID)}、
     * 用户域的 {@code authz.isOwner(用户ID)} 长得一样，把参数传串了不会报错，
     * 只会判错——所以这三个 Bean 的名字必须一眼能分清管的是哪种资源。
     */
    public boolean isOwner(Long commentId) {
        Long currentUserId = CurrentUser.id();
        if (currentUserId == null || commentId == null) {
            return false;
        }
        Comment comment = commentMapper.selectById(commentId);
        return comment != null && currentUserId.equals(comment.getAuthorId());
    }
}
