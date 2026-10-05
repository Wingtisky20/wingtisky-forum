package com.wingtisky.forum.forum.content.entity;

import java.time.LocalDateTime;

/**
 * 帖子实体，对应表 {@code t_post}。
 *
 * <p><b>这是持久层对象，不是 API 的返回形状。</b>理由与 {@code User} 一样：
 * 实体里有 {@code deleted} 这类不该出现在响应里的字段，直接返回它等于把内部结构
 * 变成对外契约。出参一律走 {@code dto/} 下的类型。
 *
 * <p><b>⚠️ 它是"部分填充"的</b>：列表查询**不查 {@code content} 列**（设计稿 §2.6，
 * 为的是让列表页永远不碰正文那个大字段），所以从列表查询拿到的 Post，
 * 它的 {@code content} 是 {@code null}。**别拿列表查询的结果去读 content。**
 */
public class Post {

    /** 正常可见。 */
    public static final int STATUS_PUBLISHED = 0;

    /** 被版主下架。与"删除"是两件事——下架可恢复，且记录还在。 */
    public static final int STATUS_OFFLINE = 1;

    private Long id;
    private Long authorId;
    private String title;

    /** 正文（Markdown 文本）。**列表查询不填这一列**，见类注释。 */
    private String content;

    /** 列表页展示的摘要，发帖时由 Service 从正文生成一次。 */
    private String summary;

    private Integer status;
    private Integer topFlag;
    private Integer featuredFlag;

    /** 浏览数。M2 用直接 {@code UPDATE + 1} 维护，M3 会换成 Redis 累加（设计稿 §2.1）。 */
    private Integer viewCount;

    /** 以下三个是**冗余计数**（ADR-0016）：列表页每行都要显示它们，
     *  实时 COUNT 会把成本压在最贵的读上。写的时候与业务操作同事务更新。 */
    private Integer likeCount;
    private Integer commentCount;
    private Integer collectCount;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删 / 1 已删。与 {@code status} 分开——见 {@code db/V2__init_content.sql} 的说明。 */
    private Integer deleted;

    /** 是否正常可见（未被下架、未被删除）。 */
    public boolean isVisible() {
        return status != null && status == STATUS_PUBLISHED
                && (deleted == null || deleted == 0);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getTopFlag() {
        return topFlag;
    }

    public void setTopFlag(Integer topFlag) {
        this.topFlag = topFlag;
    }

    public Integer getFeaturedFlag() {
        return featuredFlag;
    }

    public void setFeaturedFlag(Integer featuredFlag) {
        this.featuredFlag = featuredFlag;
    }

    public Integer getViewCount() {
        return viewCount;
    }

    public void setViewCount(Integer viewCount) {
        this.viewCount = viewCount;
    }

    public Integer getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }

    public Integer getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(Integer commentCount) {
        this.commentCount = commentCount;
    }

    public Integer getCollectCount() {
        return collectCount;
    }

    public void setCollectCount(Integer collectCount) {
        this.collectCount = collectCount;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
