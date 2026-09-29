package com.wingtisky.forum.forum.user.controller;

import com.wingtisky.forum.common.result.Result;
import com.wingtisky.forum.forum.user.dto.LoginRequest;
import com.wingtisky.forum.forum.user.dto.LoginResult;
import com.wingtisky.forum.forum.user.dto.RefreshRequest;
import com.wingtisky.forum.forum.user.dto.RegisterRequest;
import com.wingtisky.forum.forum.user.entity.User;
import com.wingtisky.forum.forum.user.service.AuthService;
import com.wingtisky.forum.forum.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p><b>四个接口的放行情况</b>（见 {@code SecurityConfig}）：
 * 注册 / 登录 / 刷新在公开名单里；注销需要认证——因为它要拿到"当前是谁"才能撤销，
 * 而这个信息只能来自令牌。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    /**
     * 注册。
     *
     * <p>只返回新用户的 ID，**不顺手发令牌**——注册成功不等于要立刻登录，
     * 让前端显式走一次登录流程，两条路径的代码就只有一条需要维护。
     */
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(request.username(), request.password(), request.nickname());
        return Result.success(user.getId());
    }

    @PostMapping("/login")
    public Result<LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request.username(), request.password()));
    }

    @PostMapping("/refresh")
    public Result<LoginResult> refresh(@Valid @RequestBody RefreshRequest request) {
        return Result.success(authService.refresh(request.refreshToken()));
    }

    /**
     * 注销。
     *
     * <p>{@code @AuthenticationPrincipal} 取的是过滤器放进 {@code SecurityContext}
     * 的 principal —— 也就是 userId。能走到这里说明认证已通过（否则被拦截器挡在门外），
     * 所以不需要再判空。
     */
    @PostMapping("/logout")
    public Result<Void> logout(@AuthenticationPrincipal Long userId) {
        authService.logout(userId);
        return Result.success();
    }
}
