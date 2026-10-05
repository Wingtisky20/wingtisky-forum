package com.wingtisky.forum.forum.content.controller;

import com.wingtisky.forum.common.result.Result;
import com.wingtisky.forum.forum.content.dto.CommentView;
import com.wingtisky.forum.forum.content.dto.CreateCommentRequest;
import com.wingtisky.forum.forum.content.dto.PageResult;
import com.wingtisky.forum.forum.content.service.CommentService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论接口。评论挂在帖子下面，所以发评论与看评论的路径都在 {@code /api/posts/{帖子id}} 之下。
 */
@RestController
@RequestMapping("/api")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * 发评论或回复。带 `parentId` 就是回复。
     *
     * <p>返回新评论的 ID。前端发完通常会把它插到列表里，所以 ID 有用。
     */
    @PostMapping("/posts/{postId}/comments")
    public Result<Long> create(@PathVariable Long postId,
                               @AuthenticationPrincipal Long userId,
                               @Valid @RequestBody CreateCommentRequest request) {
        return Result.success(
                commentService.create(postId, userId, request.parentId(), request.content()));
    }

    /** 评论列表。**公开接口**——未登录也能看评论。 */
    @GetMapping("/posts/{postId}/comments")
    public Result<PageResult<CommentView>> list(
            @PathVariable Long postId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(commentService.page(postId, page, size));
    }

    /**
     * 删评论。作者本人，或者版主 / 管理员。
     *
     * <p>注意是 {@code @commentAuthz}：三个归属校验 Bean 长得一样但管的资源不同
     * （{@code authz} 管用户、{@code postAuthz} 管帖子、{@code commentAuthz} 管评论），
     * 传串了不会报错，只会判错。
     *
     * <p>删一条顶层评论会**连带删掉它下面的回复**——这个规则在
     * {@code CommentService.delete} 里，不在接口层。接口层只负责"你有没有资格删"。
     */
    @DeleteMapping("/comments/{id}")
    @PreAuthorize("@commentAuthz.isOwner(#id) or hasAnyRole('MODERATOR', 'ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        commentService.delete(id);
        return Result.success();
    }
}
