package com.wingtisky.forum.forum.user.controller;

import com.wingtisky.forum.common.result.Result;
import com.wingtisky.forum.forum.user.dto.UpdateProfileRequest;
import com.wingtisky.forum.forum.user.dto.UserProfileResponse;
import com.wingtisky.forum.forum.user.entity.User;
import com.wingtisky.forum.forum.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户资料接口。
 *
 * <p><b>两个查询接口的形状是同一个 DTO</b>（{@link UserProfileResponse}，公开资料，
 * 不含 email）。"我的资料"页若将来要显示邮箱，那时再加一个仅自己可见的 DTO——
 * 现在加等于凭空多一份要维护的契约（YAGNI）。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 当前登录用户。
     *
     * <p>{@code @AuthenticationPrincipal} 拿到的是过滤器放进上下文的 userId。
     * 能走到这里说明认证已通过（{@code SecurityConfig} 里 {@code anyRequest().authenticated()}），
     * 因此不需要判空。
     */
    @GetMapping("/me")
    public Result<UserProfileResponse> me(@AuthenticationPrincipal Long userId) {
        return Result.success(UserProfileResponse.from(userService.getById(userId)));
    }

    /** 个人主页。公开接口——未登录也能看。 */
    @GetMapping("/{id}")
    public Result<UserProfileResponse> profile(@PathVariable Long id) {
        return Result.success(UserProfileResponse.from(userService.getById(id)));
    }

    /**
     * 修改资料。
     *
     * <p><b>这一行是本里程碑两条验收闸门之一"越权被拒"的落点。</b>
     * 没有它，任何登录用户都能改任何人的昵称——而且**不会有任何报错**。
     *
     * <p><b>为什么是 {@code @PreAuthorize} 而不是在方法体里写 if</b>：
     * <ul>
     *   <li>写在这里，它就与接口定义在同一行，读代码时不会漏看</li>
     *   <li>漏写时的表现是"这个接口没有注解"，在 review 里一眼可见；
     *       写成 if 时漏写就是"少了一个 if"，同样一眼可见但更容易发生在深层调用里</li>
     *   <li>校验逻辑本身收在 {@code AuthzService} 一处，各接口只是引用它</li>
     * </ul>
     *
     * <p><b>{@code #id} 依赖编译时保留参数名</b>（{@code -parameters}，
     * spring-boot-starter-parent 默认开启）。若哪天参数名丢失，SpEL 会解析失败并报错，
     * 而不是安静地放行——这是"失败要响"的设计。
     */
    @PatchMapping("/{id}")
    @PreAuthorize("@authz.isOwner(#id) or hasRole('ADMIN')")
    public Result<Void> updateProfile(@PathVariable Long id,
                                      @Valid @RequestBody UpdateProfileRequest request) {
        userService.updateProfile(id, request.nickname(), request.avatar(), request.email());
        return Result.success();
    }
}
