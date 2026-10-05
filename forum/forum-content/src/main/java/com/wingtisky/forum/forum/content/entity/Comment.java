package com.wingtisky.forum.forum.content.entity;

import java.time.LocalDateTime;

/**
 * 评论实体，对应表 {@code t_comment}。**两级模型**（ADR-0018）：
 * 顶层评论 + 挂在它下面的回复，不再往下嵌套。
 *
 * <h2>两个"父"字段，别混</h2>
 *
 * <table>
 *   <tr><th>字段</th><th>含义</th><th>顶层评论的值</th></tr>
 *   <tr><td>{@code parentId}</td><td>**直接**回复的那条评论</td><td>{@code 0}</td></tr>
 *   <tr><td>{@code rootId}</td><td>所属的**顶层**评论</td><td>{@code 0}</td></tr>
 * </table>
 *
 * <p><b>为什么需要 {@code rootId}</b>：只有 {@code parentId} 的话，
 * "取第 2 页的评论"要先知道哪些是顶层，而顶层判定得递归。
 * 有 {@code rootId} 之后，两个查询都变成普通索引查询（见建表脚本的
 * {@code idx_post_root}）。
 *
 * <p><b>回复一条回复时，{@code rootId} 取的是「父评论的 rootId」而不是「父评论的 id」</b>
 * （{@code 0} 时才取父评论自己的 id）。这一行写错，评论会挂到错误的楼里——
 * 详见 {@code CommentService} 里的实现与对应测试。
 */
public class Comment {

    /** 顶层评论的 {@code parentId} 与 {@code rootId} 都是这个值。 */
    public static final long NO_PARENT = 0L;

    private Long id;
    private Long postId;

    /**
     * 评论者。**这是评论的"归属"依据**——删评论时比的就是它。
     *
     * <p>删除用的是逻辑删除：这条评论被删后，其作者信息**照常**由跨域契约取回
     * （用户本身没被删），所以前端能显示"该评论已删除"而不是一片空白。
     */
    private Long authorId;

    private Long parentId;
    private Long rootId;
    private String content;
    private LocalDateTime createTime;
    private Integer deleted;

    /** 是不是一条顶层评论（它下面可以挂回复）。 */
    public boolean isTopLevel() {
        return rootId != null && rootId == NO_PARENT;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Long getRootId() {
        return rootId;
    }

    public void setRootId(Long rootId) {
        this.rootId = rootId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
