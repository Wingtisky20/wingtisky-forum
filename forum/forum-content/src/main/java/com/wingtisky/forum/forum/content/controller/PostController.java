package com.wingtisky.forum.forum.content.controller;

import com.wingtisky.forum.common.result.Result;
import com.wingtisky.forum.forum.content.dto.CreatePostRequest;
import com.wingtisky.forum.forum.content.dto.PageResult;
import com.wingtisky.forum.forum.content.dto.PostDetail;
import com.wingtisky.forum.forum.content.dto.PostListItem;
import com.wingtisky.forum.forum.content.dto.PostQuery;
import com.wingtisky.forum.forum.content.dto.PostSort;
import com.wingtisky.forum.forum.content.dto.UpdatePostRequest;
import com.wingtisky.forum.forum.content.service.PostService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帖子接口。
 *
 * <p><b>Controller 只做三件事</b>：把请求参数转成 Service 要的形状、调用 Service、
 * 把结果包进统一响应体。业务规则全在 {@link PostService}——
 * 写在这里的话，换个入口（内部接口、定时任务）就要重写一遍。
 *
 * <p><b>鉴权用注解，不写在方法体里</b>（M1 设计稿 §4.2）：注解与接口定义在同一行，
 * 漏写时一眼可见。
 */
@RestController
@RequestMapping("/api")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    /**
     * 发帖。
     *
     * <p>{@code @AuthenticationPrincipal} 拿到的是过滤器放进上下文的用户 ID。
     * 能走到这里说明认证已通过（{@code SecurityConfig} 的
     * {@code anyRequest().authenticated()}），所以不需要判空。
     *
     * <p>返回新帖子的 ID 而不是整个帖子：前端紧接着要跳转到详情页，
     * 它需要的就是这个 ID。把整个帖子再查一遍传回去是多余的一次查询。
     */
    @PostMapping("/posts")
    public Result<Long> create(@AuthenticationPrincipal Long userId,
                               @Valid @RequestBody CreatePostRequest request) {
        return Result.success(
                postService.create(userId, request.title(), request.content(), request.tags()));
    }

    /**
     * 帖子列表。**公开接口**——未登录也能看。
     *
     * <p>{@code sort} 直接声明成枚举：Spring 会做转换，传了不认识的值会报参数错误，
     * 而【枚举让外部传进来的字符串根本到不了 SQL】——ORDER BY 的列名不能用占位符，
     * 只有拼接一条路，那就等于开了注入的口子。
     */
    @GetMapping("/posts")
    public Result<PageResult<PostListItem>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) PostSort sort,
            @RequestParam(required = false) Long tagId) {
        return Result.success(postService.page(new PostQuery(page, size, sort, tagId)));
    }

    /** 帖子详情。**公开接口**。注意它会顺手给浏览数 +1。 */
    @GetMapping("/posts/{id}")
    public Result<PostDetail> detail(@PathVariable Long id) {
        return Result.success(postService.getDetail(id));
    }

    /**
     * 改帖。**只有作者能改。**
     *
     * <p>⚠️ 注意是 {@code @postAuthz} 而不是用户域的 {@code @authz}：
     * 后者的入参被当作**用户 ID**，而这里的 {@code #id} 是**帖子 ID**。
     * 用错了的话，比的是"帖子 ID 是否等于我的用户 ID"——作者自己会被拒，
     * 而极少数情况下又会错误放行（详见 {@code PostAuthzService} 的类注释）。
     *
     * <p><b>版主不能改别人的帖子</b>：那等于篡改作者的原意。治理动作应该是"下架"，
     * 不是"替他改写"（下架在 M2 Task 8）。
     */
    @PatchMapping("/posts/{id}")
    @PreAuthorize("@postAuthz.isOwner(#id)")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody UpdatePostRequest request) {
        postService.update(id, request.title(), request.content(), request.tags());
        return Result.success();
    }

    /**
     * 删帖。作者本人，**或者版主 / 管理员**。
     *
     * <p>这里是 RBAC 与资源归属**同时**出现的地方，也是它们必须分开的最好例子：
     * {@code @postAuthz.isOwner(#id)} 回答"这是不是你发的"，
     * {@code hasAnyRole(...)} 回答"你有没有治理权限"。两者是"或"的关系——
     * 满足任一个就能删。混成一个判断就没法表达这种关系了。
     */
    @DeleteMapping("/posts/{id}")
    @PreAuthorize("@postAuthz.isOwner(#id) or hasAnyRole('MODERATOR', 'ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        postService.delete(id);
        return Result.success();
    }

    /**
     * 某人发的帖子（个人主页用）。**公开接口**。
     *
     * <p>这个地址挂在 {@code /api/users/...} 下而不是 {@code /api/posts?authorId=}：
     * 前者是 REST 的子资源写法，也是更主流的形状（读作"ID 为 123 的用户的帖子"）。
     * 代价是 `/api/users` 这个前缀会同时被用户模块和内容模块占用——
     * M9 拆服务时网关需要多写一条更具体的路由，代价很小。
     */
    @GetMapping("/users/{userId}/posts")
    public Result<PageResult<PostListItem>> listByAuthor(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 个人主页按时间倒序看，不给排序选项——"某人的最热帖子"不是常见需求
        return Result.success(
                postService.pageByAuthor(userId, new PostQuery(page, size, PostSort.LATEST)));
    }
}
