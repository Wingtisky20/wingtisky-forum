package com.wingtisky.forum.forum.content.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.domain.user.UserBrief;
import com.wingtisky.forum.domain.user.UserQueryService;
import com.wingtisky.forum.forum.content.cache.PostDetailCache;
import com.wingtisky.forum.forum.content.dto.CommentReply;
import com.wingtisky.forum.forum.content.dto.CommentView;
import com.wingtisky.forum.forum.content.dto.PageResult;
import com.wingtisky.forum.forum.content.dto.PostQuery;
import com.wingtisky.forum.forum.content.entity.Comment;
import com.wingtisky.forum.forum.content.mapper.CommentMapper;
import com.wingtisky.forum.forum.content.mapper.PostMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 评论的业务规则。两级模型（ADR-0018）：顶层评论 + 回复，不再往下嵌套。
 *
 * <p>与 {@code PostService} 一样，**这里不放鉴权**——"能不能删这条评论"
 * 由接口层的 {@code @PreAuthorize("@commentAuthz.isOwner(#id)")} 回答。
 */
@Service
public class CommentService {

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final UserQueryService userQueryService;
    private final PostDetailCache postDetailCache;

    public CommentService(CommentMapper commentMapper,
                          PostMapper postMapper,
                          UserQueryService userQueryService,
                          PostDetailCache postDetailCache) {
        this.commentMapper = commentMapper;
        this.postMapper = postMapper;
        this.userQueryService = userQueryService;
        this.postDetailCache = postDetailCache;
    }

    /**
     * 发评论，或者回复某条评论——**同一个方法**，区别只在 {@code parentId} 给不给。
     *
     * <p><b>事务的理由</b>：插入评论与帖子评论数 +1 必须一起成功或一起失败。
     * 分开的话，中间断掉就会出现"评论在、计数不对"——而计数的偏差很难被注意到。
     *
     * <p><b>⚠️ 这个方法里最难的一行是 {@code rootId} 的取值</b>，见下方注释。
     * 写错的表现是"评论跑到别的楼里"——数据看着正常，只是挂错了地方。
     */
    @Transactional
    public Long create(Long postId, Long authorId, Long parentId, String content) {
        if (postMapper.selectById(postId) == null) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }

        long parent = (parentId == null) ? Comment.NO_PARENT : parentId;
        long root = Comment.NO_PARENT;

        if (parent != Comment.NO_PARENT) {
            Comment parentComment = commentMapper.selectById(parent);
            if (parentComment == null) {
                throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
            }
            // 不许跨帖回复：拿另一条帖子下的评论 id 来回复，会让这条评论挂到
            // 一个和它上下文无关的楼层下。这类请求通常来自前端 bug 或手工构造，
            // 不拦的话数据会悄悄变脏。
            if (!parentComment.getPostId().equals(postId)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "不能回复其他帖子下的评论");
            }

            // ★ 两级模型的关键一行：
            //   回复**顶层评论** → root 取被回复评论的 id（它自己就是这一楼）
            //   回复**一条回复** → root 取父评论的 rootId（**不是父评论的 id**）
            //   第二行取错的话，这条回复会变成一条新的顶层评论（root 指向一条回复的 id，
            //   而列表只查 root_id = 0，于是它既不在任何楼里、也不会出现在顶层，
            //   **直接消失**）。
            root = parentComment.isTopLevel() ? parentComment.getId() : parentComment.getRootId();
        }

        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setAuthorId(authorId);
        comment.setParentId(parent);
        comment.setRootId(root);
        comment.setContent(content);
        commentMapper.insert(comment);

        postMapper.addCommentCount(postId, 1);
        // commentCount 在缓存对象里（M3 Task 7）。不删的话，详情页会一直显示旧的评论数
        // ——列表是新发的这条已经在了，页眉的数字却还是老的，看着像统计坏了
        postDetailCache.evictAfterCommit(postId);
        return comment.getId();
    }

    /**
     * 取一页评论。**分页只作用在顶层评论上**，每层楼的回复随楼一起返回。
     */
    public PageResult<CommentView> page(Long postId, int page, int size) {
        // 夹紧规则与帖子列表相同（页码≥1、每页 1~50）。
        // 这里直接复用 PostQuery.MAX_SIZE 这个常量，不复制数字。
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PostQuery.MAX_SIZE);

        long total = commentMapper.countTopLevel(postId);
        if (total == 0) {
            return PageResult.empty(safePage, safeSize);
        }

        int offset = (safePage - 1) * safeSize;
        List<Comment> tops = commentMapper.selectTopLevelPage(postId, offset, safeSize);
        if (tops.isEmpty()) {
            // 页码超出范围（总共 5 条却要第 100 页）。**必须在这里返回**：
            // 往下走会拼出非法的 IN ()
            return new PageResult<>(List.of(), total, safePage, safeSize);
        }

        // 一次取回这些楼的全部回复——不是每层楼查一次
        Set<Long> rootIds = tops.stream().map(Comment::getId).collect(Collectors.toSet());
        List<Comment> replies = commentMapper.selectRepliesByRootIds(rootIds);

        // 一次取回所有相关作者——顶层评论与回复的作者合在一起查，只查一次
        Map<Long, UserBrief> authors = userQueryService.findBriefs(authorIdsOf(tops, replies));

        Map<Long, List<Comment>> repliesByRoot = replies.stream()
                .collect(Collectors.groupingBy(Comment::getRootId));

        List<CommentView> items = tops.stream()
                .map(top -> CommentView.from(
                        top,
                        authors.get(top.getAuthorId()),
                        repliesByRoot.getOrDefault(top.getId(), List.of()).stream()
                                .map(reply -> CommentReply.from(reply, authors.get(reply.getAuthorId())))
                                .toList()))
                .toList();

        return new PageResult<>(items, total, safePage, safeSize);
    }

    /**
     * 删评论。
     *
     * <p><b>删顶层评论会连带删掉它下面的回复</b>（用户 2026-10-05 确认的取舍）：
     * 回复脱离上下文后读不通（"他在回复什么？"），而且连带删除让**显示与计数天然一致**——
     * 否则那些回复会挂在一个看不见的父评论下，永远不显示、却仍占着评论数。
     *
     * <p>连带删掉的条数从 {@code softDeleteReplies} 的返回值得来，**不用再查一次计数**。
     */
    @Transactional
    public void delete(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
        }

        int removed = commentMapper.softDelete(commentId);
        if (comment.isTopLevel()) {
            removed += commentMapper.softDeleteReplies(commentId);
        }

        // 传负数就是减。SQL 里有 GREATEST(..., 0) 兜底，不会减成负数
        postMapper.addCommentCount(comment.getPostId(), -removed);
        // 连带删了几条回复都只删**一次**缓存——删的是同一个 key，多删没有意义
        postDetailCache.evictAfterCommit(comment.getPostId());
    }

    /** 顶层评论与回复的作者 id 合在一起，去重。 */
    private static Set<Long> authorIdsOf(List<Comment> tops, List<Comment> replies) {
        return Stream.concat(tops.stream(), replies.stream())
                .map(Comment::getAuthorId)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
