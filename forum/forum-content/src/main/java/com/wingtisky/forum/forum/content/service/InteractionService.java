package com.wingtisky.forum.forum.content.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.forum.content.mapper.PostCollectMapper;
import com.wingtisky.forum.forum.content.mapper.PostLikeMapper;
import com.wingtisky.forum.forum.content.mapper.PostMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 互动：点赞与收藏。
 *
 * <p><b>为什么不放进 {@code PostService}</b>：那边已经管着发帖、改帖、删帖、列表、
 * 详情、标签六件事了。再加点赞收藏，它会变成"什么都管"的那个类，
 * 而且得同时认识三张表。
 *
 * <h2>四个动作共有的两条规矩</h2>
 *
 * <ol>
 *   <li><b>幂等由数据库保证</b>（ADR-0017）：重复点赞靠
 *       {@code INSERT IGNORE} 撞联合主键后安静跳过，取消点赞靠
 *       {@code DELETE} 删 0 行也不报错。应用层不做"先查一查"——
 *       那中间有窗口。</li>
 *   <li><b>只有真的写进去了才动计数</b>。用 SQL 的返回行数判断：
 *       插入成功 1、被忽略 0；删除删到了 1 行、没删到 0 行。
 *       **少了这个判断，同一个人点十次赞，帖子的点赞数就是十。**</li>
 * </ol>
 *
 * <p><b>删帖时这些记录不删</b>（用户 2026-10-06 定的）：帖子上的"点赞数"只在
 * 帖子可见时才有意义，而删掉的帖子不可见；记录留着反而保住了"以后恢复帖子"的可能
 * ——这与本项目一律用标记删除而不是真删的思路一致。
 * （注意这和标签不一样：标签的计数是**全站可见**的，所以删帖必须减。）
 */
@Service
public class InteractionService {

    private final PostMapper postMapper;
    private final PostLikeMapper postLikeMapper;
    private final PostCollectMapper postCollectMapper;

    public InteractionService(PostMapper postMapper,
                              PostLikeMapper postLikeMapper,
                              PostCollectMapper postCollectMapper) {
        this.postMapper = postMapper;
        this.postLikeMapper = postLikeMapper;
        this.postCollectMapper = postCollectMapper;
    }

    /** 点赞。已经点过就当无事发生（返回成功，计数不再加）。 */
    @Transactional
    public void like(Long postId, Long userId) {
        requirePostExists(postId);
        if (postLikeMapper.insertIgnore(userId, postId) > 0) {
            postMapper.addLikeCount(postId, 1);
        }
    }

    /** 取消点赞。本来就没点过也算成功（幂等），但**不能去减计数**。 */
    @Transactional
    public void unlike(Long postId, Long userId) {
        requirePostExists(postId);
        if (postLikeMapper.delete(userId, postId) > 0) {
            postMapper.addLikeCount(postId, -1);
        }
    }

    /** 收藏。已收藏过则无事发生。 */
    @Transactional
    public void collect(Long postId, Long userId) {
        requirePostExists(postId);
        if (postCollectMapper.insertIgnore(userId, postId) > 0) {
            postMapper.addCollectCount(postId, 1);
        }
    }

    /** 取消收藏。 */
    @Transactional
    public void uncollect(Long postId, Long userId) {
        requirePostExists(postId);
        if (postCollectMapper.delete(userId, postId) > 0) {
            postMapper.addCollectCount(postId, -1);
        }
    }

    /**
     * 这个人点过赞吗。**未登录（{@code userId} 为 null）直接返回 false、不查库**——
     * 详情接口是公开的，匿名访问时没必要为了一个"你没登录"的结论去查一次数据库。
     */
    public boolean liked(Long postId, Long userId) {
        return userId != null && postLikeMapper.exists(userId, postId) > 0;
    }

    /** 这个人收藏过吗。未登录同样不查库。 */
    public boolean collected(Long postId, Long userId) {
        return userId != null && postCollectMapper.exists(userId, postId) > 0;
    }

    /**
     * 帖子必须存在且未被删除——对一条不存在的帖子点赞应当报"帖子不存在"，
     * 而不是安静地往一张孤儿关联表里插一行。
     */
    private void requirePostExists(Long postId) {
        if (postMapper.selectById(postId) == null) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
    }
}
