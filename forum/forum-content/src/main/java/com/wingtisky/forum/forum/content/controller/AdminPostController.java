package com.wingtisky.forum.forum.content.controller;

import com.wingtisky.forum.common.result.Result;
import com.wingtisky.forum.forum.content.dto.AdminPostUpdateRequest;
import com.wingtisky.forum.forum.content.service.PostService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台治理接口：置顶 / 加精 / 上下架。**只有接口，没有页面**（M2 的取舍）。
 *
 * <p><b>为什么单独一个 Controller，而不是塞进 {@code PostController}</b>：
 * 后台接口集中在一处，将来加治理动作（删评论、封账号…）不用去业务接口那个类里翻。
 * 更重要的是——**权限的范围一目了然**：这个类里每一个接口都是同一个门槛。
 *
 * <p>{@code @PreAuthorize} 写在**类上**而不是每个方法上：这一整个类都是后台接口，
 * 逐个方法写注解的失败模式是"新加一个方法时忘了写"，而那种漏洞不会报错，
 * 只会让一个治理接口对所有人敞开。写在类上就不存在"忘了"。
 *
 * <p><b>注意它和作者改帖是两条完全不同的路径</b>：作者改的是内容
 * （`PATCH /api/posts/{id}`，要归属校验），版主改的是属性（这里，要角色）。
 * 这也是为什么"版主不能改别人的帖子正文"——治理动作应该是下架，
 * 而不是替作者改写。
 */
@RestController
@RequestMapping("/api/admin/posts")
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
public class AdminPostController {

    private final PostService postService;

    public AdminPostController(PostService postService) {
        this.postService = postService;
    }

    /**
     * 改帖子的治理属性。给哪个字段就改哪个。
     *
     * <p>用 {@code PATCH} 而不是三个接口（`/top`、`/feature`、`/offline`）：
     * 它们改的是同一份数据的相邻字段，分开会让"同时置顶并加精"变成两次请求，
     * 而这两次请求之间可能出现一次失败，留下一个不想要的中间状态。
     */
    @PatchMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @AuthenticationPrincipal Long operatorId,
                               @Valid @RequestBody AdminPostUpdateRequest request) {
        postService.administrate(id, operatorId,
                request.topFlag(), request.featuredFlag(), request.status());
        return Result.success();
    }
}
