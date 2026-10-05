# 链路讲解 · 鉴权：RBAC 与资源归属

## 一句话

一次请求进来，鉴权要回答的是两个**不同**的问题：**你有没有资格做这类事**（RBAC，看角色）、**这件事的东西是不是你的**（资源归属，看数据）。前者用 `hasRole('ADMIN')`，后者用 `@authz.isOwner(#id)`。这篇讲这两层各在哪、怎么串起来，以及为什么 `PATCH /api/users/me` 被改成了 `PATCH /api/users/{id}`。

## 数据怎么走

一次 `PATCH /api/users/2`（带着 `Authorization: Bearer <access>`）：

```
HTTP 请求  Authorization: Bearer xxx
  │
  ▼
JwtAuthenticationFilter            ← 只验签，不拒绝任何请求
  │  解出 userId=1、roles=[USER]
  │  把 principal=1、authorities=[ROLE_USER] 塞进 SecurityContext
  ▼
SecurityConfig 的 authorizeHttpRequests   ← 配置级闸门
  │  /api/users/2 不在 PUBLIC_ENDPOINTS，命中 anyRequest().authenticated()
  │  上下文里有认证 → 放行
  ▼
DispatcherServlet → UserController.updateProfile
  │  @PreAuthorize("@authz.isOwner(#id) or hasRole('ADMIN')")
  │  SpEL 调 AuthzService.isOwner(2)
  │  当前 userId=1，1.equals(2) 为 false；也没有 ADMIN → 抛 AccessDeniedException
  ▼
AccessDeniedHandler → 403，code=A0003（RestAuthErrorWriter 写统一响应体）
```

校验通过的话继续往下：`UserController.updateProfile` → `UserService.updateProfile` → `UserMapper.updateProfile` → MySQL。注意 **Service 里不再判一次归属**——它只管写。

这里有个岔路必须分清，因为两条路都被拒，但语义完全不同：

- **没带令牌 / 令牌坏了**：过滤器验不通，什么都不设 → 配置级规则判定"未认证" → `AuthenticationEntryPoint` → **401 A0002**
- **令牌有效，但改的不是自己的资源**：过滤器设好了认证 → 规则放行 → 方法上的 `@PreAuthorize` 拦下 → `AccessDeniedHandler` → **403 A0003**

401 是"我不知道你是谁"，403 是"我知道你是谁，但你不该做这个"。这个区分由 Spring Security 免费给出，前提是我们在两个位置各放了一个 handler（`SecurityConfig` 里的 `entryPoint` 与 `deniedHandler`）。

## 逐段讲代码

### 1. 过滤器只做认证，不做授权

`JwtAuthenticationFilter.doFilterInternal` 的核心就三行：

```java
String token = extractToken(request);
if (token != null) {
    authenticate(token);
}
filterChain.doFilter(request, response);
```

**它不拒绝任何请求**——能验通就设上下文，验不通就什么都不做，然后一律继续往下走。为什么？见类注释：如果过滤器自己返回 401，就等于把"哪些接口不需要登录"这条规则写在了两个地方（过滤器 + `SecurityConfig`），两处迟早不一致，而不一致的表现是"某个公开接口突然要登录了"，或者更糟的"某个该保护的接口被放过了"。规则集中一处，是这个分工的全部理由。

`authenticate` 里最关键的一行是 `ROLE_` 前缀：

```java
private static final String ROLE_PREFIX = "ROLE_";
...
List<SimpleGrantedAuthority> authorities = roles.stream()
        .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
        .toList();
```

`hasRole('ADMIN')` 比对的实际字符串是 `ROLE_ADMIN`，前缀由框架自动补在 `hasRole` 那一侧。构造 `SimpleGrantedAuthority` 时必须自己写上，**少写则所有 `hasRole` 判断静默失败**，症状是"明明有权限却说没权限"。同一条约定在 `RoleCode` 的类注释里又写了一遍——这是有意的重复，一处易漏、两处互证。

principal 放的是 `userId`：

```java
UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(userId, null, authorities);
authentication.setDetails(username);
```

principal 类型是 `Long`，这正是后面 `AuthzService` 能拿到"当前用户 id"的前提。credentials 传 `null`：令牌已经验过了，留着只会多一个被日志打出来的风险，没有意义。catch 分支只记 debug（过期是每天的常态，记 warn 会把日志淹没），并且**清空上下文**——容器复用线程，不清会沿用上一个请求的认证信息。

### 2. roles 为什么放进 JWT 载荷

`JwtTokenProvider.issueAccessToken`：

```java
String token = Jwts.builder()
        .subject(String.valueOf(userId))
        .claim("username", username)
        .claim(CLAIM_ROLES, roles)
        .id(jti)
        ...
```

角色写进令牌，验签后直接就能构造权限，**不必每个请求查一次库**——这正是"无状态"的价值，也是 `JwtTokenProvider` 类注释和 ADR-0013 的落点。代价同样明确：改了角色要等令牌过期（最长 access-ttl）才生效。

对照 `issueRefreshToken`——刷新令牌**不放 roles**。它只用来换新的 access，权限在那一刻重新查库取最新的；放进去反而会固化一个可能已经过期的角色。

### 3. 配置级闸门：默认全要认证，只白名单敞开

`SecurityConfig.securityFilterChain` 里的授权规则：

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
        // 个人主页是公开的：未登录也能看别人的主页
        .requestMatchers(HttpMethod.GET, "/api/users/*").permitAll()
        // 剩下的全部需要认证。**用 anyRequest().authenticated() 而不是
        // 逐个列出要保护的路径**——漏列一个的后果是那个接口完全敞开，
        // 而这种漏洞不会报错，只会静静存在。
        .anyRequest().authenticated())
```

顺序是"先放行明确公开的，其余一律要认证"。`PUBLIC_ENDPOINTS` 是注册/登录/刷新三条；`GET /api/users/*` 单独一条，因为个人主页要公开——注意这条通配**同时也覆盖了 `/api/users/me`**（`me` 匹配 `*`）。

真正为方法级校验开门的是这一行：

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // 开启 @PreAuthorize。Task 5 的资源归属校验依赖它
```

少了 `@EnableMethodSecurity`，`@PreAuthorize` 会被**静默忽略**——注解还在、编译也过、就是不生效。这是最危险的一类失效，所以它值一行注释。

### 4. 归属判断收进一个 Bean

`AuthzService`，Bean 名显式定为 `authz`：

```java
@Component("authz")
public class AuthzService {

    public boolean isOwner(Long ownerId) {
        Long currentUserId = currentUserId();
        return currentUserId != null && currentUserId.equals(ownerId);
    }

    public Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return (principal instanceof Long userId) ? userId : null;
    }
}
```

看清楚它在比什么：**把路径上的 id 和当前登录者的 id 作比较**。对"用户资料"这个资源来说，资源标识就是 `userId`，所以"归属"就等于"路径里的 id 是不是我"。等 M2 有了帖子，`isOwner` 的入参会换成帖子的 `authorId`，比对逻辑一模一样——变的只是"资源所有者从哪里来"。

三个值得说的细节：

- **未登录返回 `false`，不抛异常**。它会被 SpEL 调用，保持"只回答是或否"最简单。至于"未登录该返回 401 还是 403"，由 Spring Security 的授权规则决定，不该在这里判。
- **用 `instanceof` 而不是直接强转**。将来若有人把 principal 换成 `UserDetails`，强转会抛 `ClassCastException`；类型判断则让它安静返回 `null`（即"不是预期类型，视作未登录"），调用点不用去解释一个奇怪的强转异常。
- **Bean 名写成 `authz` 而非默认真 `authzService`**：SpEL 里 `@authz.isOwner(#id)` 更短，在调用点也更醒目。

**为什么把归属判断收进一个 Bean，而不是各处手写 `if`** —— 这是本条的题眼。理由在 `AuthzService` 类注释和 `docs/03-design/m1-user-and-security.md §4.2`：手写的失败模式是**静默的**，漏写一处不会有任何报错，直到被人利用。收进一处之后，"忘了加校验"会变成一个显式的遗漏（代码 review 和测试都能看出来），而不是悄悄少了一行。**不是为优雅，是为了不漏。**

### 5. 调用点：注解就贴在接口方法上

`UserController.updateProfile`：

```java
@PatchMapping("/{id}")
@PreAuthorize("@authz.isOwner(#id) or hasRole('ADMIN')")
public Result<Void> updateProfile(@PathVariable Long id,
                                  @Valid @RequestBody UpdateProfileRequest request) {
    userService.updateProfile(id, request.nickname(), request.avatar(), request.email());
    return Result.success();
}
```

这一行把两层并排写在一起，读的时候一眼能看清：`isOwner(#id)` 是归属，`hasRole('ADMIN')` 是 RBAC。管理员和本人**都能改**，其余人都不能——这正是"两层不能混"的具体样子：只用 RBAC 表达不了"本人"（本人不是任何角色），只用归属表达不了"管理员能改别人的"。

`#id` 能解析，依赖编译时保留参数名（`-parameters`，`spring-boot-starter-parent` 默认开启）。若参数名丢失，SpEL 会**解析失败并报错**，而不是安静放行——这是有意的"失败要响"。

上一层的 `me` 接口则完全不做归属校验，因为它没有资源标识可校验：

```java
@GetMapping("/me")
public Result<UserProfileResponse> me(@AuthenticationPrincipal Long userId) {
    return Result.success(UserProfileResponse.from(userService.getById(userId)));
}
```

`@AuthenticationPrincipal` 拿到的就是过滤器放进上下文的那个 `Long userId`。能走到这里说明认证已经通过（`anyRequest().authenticated()`），所以这里不判空。

### 6. Service 不做鉴权

`UserService.updateProfile`：

```java
@Transactional
public void updateProfile(Long userId, String nickname, String avatar, String email) {
    if (nickname == null && avatar == null && email == null) {
        throw new BizException(ErrorCode.PARAM_INVALID, "至少提供一个要修改的字段");
    }
    ...
    userMapper.updateProfile(patch);
}
```

它**不判断"调用者有没有资格改这个 id"**。这条边界见方法注释：那是鉴权层的问题（`@PreAuthorize("@authz.isOwner(#id)")`），混进 Service 的后果是校验散落在每个方法里，漏掉一个也不会有任何报错。Service 只假定一件事：能走到我这儿的请求，权限已经过了。

## 走一遍场景

先用冒烟脚本（`docs/06-runbook/api-smoke.http`）的第 12、13 条确认两个基本事实：

- **第 12 条** `GET /api/users/1`，不带令牌 → 期望 200，且返回体里没有 `password` 字段。证明个人主页是公开的，`GET /api/users/*` 那条 `permitAll` 生效。
- **第 13 条** `GET /api/users/me`，带 `Authorization: Bearer {{access}}` → 期望 200。证明令牌能建立上下文，`@AuthenticationPrincipal` 拿得到 id。

现在用用户 A（id=1）的令牌，去改用户 B（id=2）的资料：

```
PATCH /api/users/2
Authorization: Bearer <A 的 access>

{ "nickname": "被改了" }
```

过滤器解出 userId=1、roles=[USER]，上下文就绪。配置级规则放行（已认证）。到方法上，SpEL 求值 `@authz.isOwner(2)`：当前 userId 是 1，`1.equals(2)` 为 false；`hasRole('ADMIN')` 也为 false（A 只有 USER）→ 整个表达式为 false → 抛 `AccessDeniedException` → `AccessDeniedHandler` → **403，code=A0003**。

反面对照，用同一条令牌改自己：

```
PATCH /api/users/1

{ "nickname": "我自己改的" }
```

`isOwner(1)` 为 true → 放行 → 走到 `UserService.updateProfile(1, ...)` → `UPDATE t_user ... WHERE id = 1 AND deleted = 0` → **200**。

这两个必须成对看（`docs/03-design/m1-user-and-security.md §9` 把"反面对照"列为硬要求）：只证明"越权被拒"证明不了任何东西——一个永远返回 403 的系统也能通过。必须同时证明"该通过的确实通过"。

> **`PATCH /me` 为什么被废掉**：`/me` 的路径里**没有资源标识**，"改错人"这种情况根本不存在——`isOwner` 拿到的永远是自己，校验**永远不会失败**，等于没写。而 M1 的验收闸门恰恰是"越权访问实测被拒"，需要一个**能真的失败**的路径。完整取舍见 `m1-user-and-security.md §7`。

## 面试会追问

1. **归属校验为什么不做在 Service 里？** 因为失败模式是静默的——散在每个方法里的 `if` 漏一个不报错，只会在某天被人利用。收进 `AuthzService` 一处，漏掉就变成显式的（注解不在那儿，review 和测试都能看出来）。见 §4.2。
2. **`PATCH /api/users/me` 有什么问题？** 路径里没有资源标识，归属判断恒为真，等于没有校验。所以改成 `/{id}`——代价是前端要多存一个 id（它本来就从 `GET /me` 拿到了）。
3. **`isOwner` 会查库去拿资源的作者吗？** 在这个接口里不会——用户资料的资源标识就是 `userId`，直接比路径 id 和 principal。等 M2 有帖子了，才会拿 `authorId` 来比，比对逻辑不变，变的只是"资源所有者从哪来"。
4. **未登录时 `isOwner` 返回什么？谁决定 401 还是 403？** 返回 `false`，不抛异常。401/403 由 Spring Security 的授权规则决定，不在这层判。
5. **`hasRole('ADMIN')` 为什么必须给 authority 加 `ROLE_` 前缀？** 框架比对的是 `ROLE_ADMIN`，前缀由它自动补在 `hasRole` 那一侧；构造 `SimpleGrantedAuthority` 时必须自己写，少写则所有角色判断静默失败。
6. **把 `@EnableMethodSecurity` 删了会怎样？** `@PreAuthorize` 会被静默忽略——注解还在、编译通过、但不生效。这就是它必须在配置里显式出现的原因。
7. **roles 为什么放 JWT 载荷？代价是什么？** 验签后即可构造权限、不必每请求查库，这是无状态的价值（ADR-0013）。代价是改角色要等令牌过期才生效。
8. **过滤器验不通为什么不直接返回 401？** 那样"哪些接口公开"的规则就有两份（过滤器 + 配置），迟早不一致，而不一致的表现可能是"该保护的接口被放过了"。
9. **401 和 403 在这条链路里分别从哪来？** 没令牌 / 令牌坏 → 认证不成立 → `AuthenticationEntryPoint` → 401 A0002；令牌有效但改的不是自己的 → 认证成立、方法级拒绝 → `AccessDeniedHandler` → 403 A0003。
10. **顺带一个真实的边角**：`GET /api/users/*` 那条放行也覆盖了 `/me`。所以不带令牌调 `GET /me` 不会被 401 拦住，而是走到 `me(null)` → `userService.getById(null)` → `selectById` 的 `WHERE id = NULL` 命中零行 → `USER_NOT_FOUND` → **404**。它不会放大权限，但值得知道——路径通配的范围往往比写它的人以为的宽。

## 引用

- `docs/03-design/m1-user-and-security.md` §4.1（两层鉴权）、§4.2（越权校验写在哪）、§7（`/me` 改 `/{id}` 的理由）、§9（反面对照）
- `docs/00-charter/collaboration.md` §2.6（链路讲解的规矩）
- `ADR-0013`（JWT 双令牌：roles 进载荷与无状态的取舍）
- spec §6.2（M1 验收闸门：越权被拒）
- `docs/06-runbook/api-smoke.http` 第 12、13 条

> 本文与代码对齐于 M1 完成时（`git tag m1-done` 附近）。`tools/check-doc-refs.sh` 会把它纳入扫描——
> 若将来重命名了 `AuthzService` / `isOwner`，这里会先报错，而不是静静地骗人。
