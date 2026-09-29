# M1 · 用户域与安全基座 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让项目拥有**第一行业务代码**——注册登录、JWT 认证、RBAC 鉴权、统一响应与异常体系，以及**亮点 5（Redis ZSet 滑动窗口限流）**落地。M1 是后续所有接口的基座：M2 的帖子、M7 的订单、M8 的秒杀，每个接口都要过它。

**工期：** 3 周（spec §6.2，**预估值**）。M1 结束时必须用实测速度重新校准后续里程碑并写回 `STATUS.md`。

**Architecture:** M1–M8 单进程逻辑态（architecture.md §3.1）。业务代码落在 `forum/forum-user`；跨域契约（当前为空）落在 `wt-domain`；Redis / JWT 等**机制**落在 `wt-infra`，而"限流阈值多少""令牌活多久"这类**策略**留在业务模块——这是 architecture.md §4.3 画的边界线。

**设计依据：** `docs/03-design/m1-user-and-security.md`（**先于本计划定稿，本计划不重复其推理**）。本计划只回答"怎么把它做出来"。

**Tech Stack:** Java 17 · Spring Boot 3.5.16 · Spring Security 6 · MyBatis · MySQL 8.0.41 · Redis 5.0.14.1 · JUnit 5 · ArchUnit 1.5.0

---

## Global Constraints

以下为项目级硬约束，**每个任务都隐式包含**：

- **Java 17**。**禁止 `javax.*`**，一律 `jakarta.*`。
- **Spring Security 6**：配置用 `SecurityFilterChain` Bean，**禁止 `WebSecurityConfigurerAdapter`**（已被删除）。
- **高危区点名（spec §9 闸门 5）**：本文涉及的 Spring Security 6 配置、MyBatis starter 版本、MySQL 驱动与 8.0.41 服务端的兼容性、Redis 与 Redisson 的版本约束——**先读官方文档或本地 jar 源码，再写代码**。凭记忆写出来的 Spring Security 6 配置几乎一定是错的（网上大量教程仍是 Boot 2 时代的写法）。
- **依赖防幻觉（spec §9 闸门 3）**：新增依赖必须 Maven Central 真实存在 + `mvn dependency:tree` 可解析 + 版本收在父 POM 的 `dependencyManagement`。**禁止"我记得有个库叫…"**。
- **证据制（spec §9 闸门 1）**：任何"已完成"结论必须附**可复现命令 + 真实输出**。禁止"应该可以了"。
- **反验证剧场（spec §9 闸门 2）**：M1 的两条闸门（限流 429、越权被拒）**必须附反面对照**——只证明"被拒绝"没有意义，一个永远返回 403 的系统也能通过。必须同时证明"该通过的确实通过"。
- **用户确认闸门（优先级高于任何执行技能）**：**每个任务动手前**必须先向用户说清三件事并**等回应才动手**：① 这步做什么 ② 为什么现在做、不做会怎样 ③ 有哪几种做法、建议哪种、代价是什么。`subagent-driven-development` 等技能默认"任务之间不停下确认"——**该默认在本项目失效**。
- **提交拆分**：一个「功能」通常应产生 **3-8 个提交**。可拆维度：契约先行 / 领域与装配分离 / 测试独立 / **修复独立** / 文档独立。**每一刀都必须保持可编译**。
- **提交与 diff 对账（spec §9 闸门 4）**：提交前逐条对照 `git diff`。
- **敏感信息绝不入库（spec §10.9）**：数据库密码、`JWT_SECRET` 走**环境变量**，代码里只读 `${...}`；提供 `.env.example`（只有占位符），`.env` 进 `.gitignore`。
- **中间件按需启动**：M1 只需要 **MySQL + Redis**，**不需要 ES 与 Kafka**（architecture.md §6.2）。
- **停止进程一律按端口找 PID**：`netstat -ano | grep ':端口 ' | grep LISTENING` 取 PID 再 `taskkill //PID <pid> //F`。**禁止 `taskkill /IM java.exe`**——会连带杀掉 IDEA 等所有 java 进程。
- **已知命令坑**（M0 实测，勿照抄记忆）：
  1. `-Dtest=XxxTest` 配 `-am` 时，上游无匹配测试的模块会让 surefire 失败 → 必须加 **`-Dsurefire.failIfNoSpecifiedTests=false`**
  2. `mvn clean compile` **不产 jar**；凡用 `ls */target/*.jar` 当证据处，必须先 `package`
  3. `mvn -pl app -am spring-boot:run` **不可用**（`-am` 把无 main class 的根聚合 POM 拽进 reactor）→ 用 `mvn -pl app -am package -DskipTests` 后 `java -jar app/target/app-1.0.0-SNAPSHOT.jar`

---

## 新增依赖（**已核实存在，待用户确认**）

按 spec §9 闸门 3，逐个查过 `maven-metadata.xml` 与 POM，**不是凭记忆**：

| 依赖 | 版本 | 来源 | 核实结论 |
|---|---|---|---|
| `spring-boot-starter-security` | 3.5.16 | 由父 POM 管理 | `.../3.5.16/*.pom` → HTTP 200 |
| `spring-boot-starter-validation` | 3.5.16 | 由父 POM 管理 | HTTP 200 |
| `spring-boot-starter-data-redis` | 3.5.16 | 由父 POM 管理 | HTTP 200；内含 **Lettuce 6.6.0.RELEASE** |
| `mybatis-spring-boot-starter` | **3.0.5** | 需显式写版本 | 其父 POM 声明 `spring-boot.version=3.5.0` ✅ |
| ~~`mybatis-spring-boot-starter`~~ | ~~4.1.0~~ | — | ❌ 其父 POM 声明 `spring-boot.version=4.1.0`——**是给 Boot 4 的，不能选"最新"** |
| `mysql-connector-j` | 9.7.0 | 由父 POM 管理（`mysql.version`） | Boot 3.5.16 的 `dependencyManagement` 中即此值 |
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` | 见 Task 4 | 需显式写版本 | `0.12.7` 与 `0.13.0` 均存在 |

### ⚠️ 两个需要用户拍板的依赖问题

**问题一：Redis 客户端用哪个？**

白名单（CLAUDE.md）写的是「Redis · Redisson · Caffeine」，**没有写 `spring-boot-starter-data-redis`**。

- **建议**：**引入 `spring-boot-starter-data-redis`（Lettuce）用于 M1 的限流与 refresh token 存取；Redisson 留到 M8 的分布式锁**。
- 理由：限流需要执行 Lua 脚本操作 ZSet，`RedisTemplate` + `DefaultRedisScript` 是标准路径且资料最多；Redisson 的强项是分布式锁与分布式对象，用它做限流反而绕。
- **代价**：同时存在两个 Redis 客户端（Lettuce + Redisson），M8 起需要在配置上分清各自用途。

**问题二：JWT 用哪个库？**

- **建议 A：`jjwt`（io.jsonwebtoken）**——纯工具库，自己写过滤器链，签发与校验的每一步都在明面上，**面试时能讲清整条链路**。代价：多一个第三方依赖。
- **方案 B：Spring Security 自带的 `spring-security-oauth2-jose`（Nimbus）**——不额外引第三方，但它的设计假设你有一个 OAuth2 授权服务器（Resource Server 自动配置），本项目的场景需要绕开不少默认行为，**可讲的部分反而变少**。

**建议 A。** 两个问题都需要用户点头后才动手（Global Constraints 的依赖规矩）。

---

## File Structure

本里程碑创建/修改的文件及其职责：

```
WingtiskyForum/
├─ pom.xml                                     父 POM：dependencyManagement 增补新依赖
├─ .env.example                                环境变量占位模板（DB / Redis / JWT_SECRET）
├─ .gitignore                                  追加 .env
├─ docs/02-decisions/
│  ├─ ADR-0012-persistence-mybatis.md          ★ 持久层选型（补 spec §12 的时点遗漏）
│  ├─ ADR-0013-jwt-dual-token.md               ★ 双令牌与注销策略
│  └─ ADR-0014-rate-limit-degradation.md       ★ 限流降级策略（按接口分类）
├─ docs/03-design/m1-user-and-security.md      设计稿（已定稿）
├─ docs/06-runbook/local-setup.md              补 MySQL 建库与 Redis 启动说明
├─ db/
│  └─ V1__init_user.sql                        建库建表脚本（含索引与注释）
├─ wt-common/src/main/java/com/wingtisky/forum/common/
│  ├─ result/Result.java                       统一响应体
│  ├─ result/ErrorCode.java                    错误码（分段）
│  ├─ exception/BizException.java              业务异常
│  ├─ exception/GlobalExceptionHandler.java    @RestControllerAdvice
│  └─ trace/TraceIdFilter.java                 traceId 生成 + MDC
├─ wt-infra/src/main/java/com/wingtisky/forum/infra/
│  ├─ redis/RedisKey.java                      Key 命名规范（机制，不是策略）
│  └─ redis/RateLimitScript.java               限流 Lua 脚本的加载与封装
├─ forum/forum-user/src/main/java/com/wingtisky/forum/forum/user/
│  ├─ entity/User.java  entity/Role.java
│  ├─ mapper/UserMapper.java  mapper/RoleMapper.java
│  ├─ service/UserService.java  service/AuthService.java
│  ├─ security/JwtTokenProvider.java          签发 / 解析 / 校验
│  ├─ security/JwtAuthenticationFilter.java   OncePerRequestFilter
│  ├─ security/SecurityConfig.java            SecurityFilterChain Bean
│  ├─ security/AuthzService.java              资源归属校验（Bean 名 authz）
│  ├─ ratelimit/RateLimit.java                注解
│  ├─ ratelimit/RateLimitInterceptor.java     拦截器 + 降级策略
│  ├─ controller/AuthController.java  controller/UserController.java
│  └─ dto/                                    请求 / 响应 DTO（绝不返回实体）
├─ forum/forum-user/src/main/resources/mapper/  MyBatis XML（手写 SQL）
├─ forum/forum-user/src/test/java/...          单测 + 集成测试
└─ app/src/main/resources/application.yml      数据源 / Redis / JWT 配置（敏感项走环境变量）
```

---

## Task 1: 公共设施（响应体 / 错误码 / 异常 / traceId）

**Files:**
- Create: `wt-common/src/main/java/com/wingtisky/forum/common/result/Result.java`
- Create: `wt-common/src/main/java/com/wingtisky/forum/common/result/ErrorCode.java`
- Create: `wt-common/src/main/java/com/wingtisky/forum/common/exception/BizException.java`
- Create: `wt-common/src/main/java/com/wingtisky/forum/common/exception/GlobalExceptionHandler.java`
- Create: `wt-common/src/main/java/com/wingtisky/forum/common/trace/TraceIdFilter.java`

**Interfaces:**
- Produces: `Result<T>`（`code` / `message` / `data` / `traceId`）、`ErrorCode` 枚举、`BizException`、`GlobalExceptionHandler`、`TraceIdFilter`
- **后续所有模块都依赖这一层**，所以先做，且一旦定型不再改（改它 = 改所有接口的返回格式）

**注意**：`GlobalExceptionHandler` 是 Spring MVC 的东西，而 `wt-common` 目前**不依赖 web**（Task 1 的依赖表：「`wt-common` 只依赖 `spring-boot-starter`，不引 web」）。因此需要给 `wt-common` 增加 `spring-boot-starter-web`（`provided` 或 `compile`），**或**把 `GlobalExceptionHandler` 放到 `app`。

> **建议**：给 `wt-common` 加 `spring-boot-starter-web`（compile 作用域）。理由：统一异常处理是"公共设施"的一部分，放在 `app` 会让 app 从"装配层"变成"也写代码的层"，边界变浑。代价：`wt-common` 从此绑定 web——但它本来就是给 web 用的工具层。

- [ ] **Step 0: 向用户确认**（含上面这个"wt-common 要不要引 web"的取舍）

**Step 1: `ErrorCode`**——分段，不堆枚举

```java
public enum ErrorCode {
    // A0001~A0099 通用
    PARAM_INVALID(A0001, "参数不合法"),
    UNAUTHORIZED(A0002, "未登录或登录已过期"),
    FORBIDDEN(A0003, "没有权限"),
    TOO_MANY_REQUESTS(A0004, "请求过于频繁，请稍后再试"),
    SYSTEM_ERROR(A0005, "系统繁忙"),
    // A0100~A0199 用户域
    USERNAME_EXISTS(A0101, "用户名已被占用"),
    LOGIN_FAILED(A0102, "用户名或密码错误"),
    USER_NOT_FOUND(A0103, "用户不存在"),
    ACCOUNT_DISABLED(A0104, "账号已被禁用"),
    ;
}
```

**注意**：`LOGIN_FAILED` 对"用户名不存在"和"密码错误"**返回同一错误码与文案**——否则接口会变成"用户名探测器"，攻击者能枚举出哪些用户名存在。这条要在注释里写明白。

**Step 2: `Result<T>`** + **Step 3: `BizException`** + **Step 4: `GlobalExceptionHandler`**

- 兜底 `Exception` → `SYSTEM_ERROR`，**日志里打完整堆栈，响应里只给错误码与 traceId**（堆栈给前端既是安全问题也是体验问题）
- 单独处理 `MethodArgumentNotValidException`（参数校验）与 `AccessDeniedException`（越权，必须映射成 403 而不是 500）

**Step 5: `TraceIdFilter`**——生成 traceId → MDC → 响应头 → 请求结束清理。日志格式里带上 `%X{traceId}`。

- [ ] **Step 6: 验证**

```bash
mvn -q -pl wt-common -am package -DskipTests && echo "编译通过"
# 启动后打一个不存在的路径，确认返回的是统一响应体而不是 Spring 默认错误页
java -jar app/target/app-1.0.0-SNAPSHOT.jar &
sleep 25
curl -s http://localhost:8080/api/not-exist        # → 统一 JSON，含 code/message/traceId
PID=$(netstat -ano | grep ':8080 ' | grep LISTENING | awk '{print $5}' | head -1); taskkill //PID "$PID" //F
```

**Step 7: 提交**（拆 3 条：错误码+响应体 / 异常处理 / traceId）

---

## Task 2: 持久层接入（数据源 + MyBatis + 建表）

**Files:**
- Create: `db/V1__init_user.sql`
- Create: `app/src/main/resources/application.yml`
- Create: `.env.example`、修改 `.gitignore`
- Modify: `pom.xml`（新依赖 + `dependencyManagement`）
- Create: `wt-infra/.../redis/RedisKey.java`（Key 命名规范，**机制**）

**Interfaces:**
- Produces: 可用的 `DataSource`、`SqlSessionFactory`、Redis 连接；三张表的真实结构

- [ ] **Step 0: 向用户确认**

**Step 1: 父 POM 增补依赖**（版本收在 `dependencyManagement`，子模块不写版本）

```xml
<properties>
  <mybatis-spring-boot.version>3.0.5</mybatis-spring-boot.version>
  <jjwt.version>0.12.7</jjwt.version>
</properties>
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.mybatis.spring.boot</groupId>
      <artifactId>mybatis-spring-boot-starter</artifactId>
      <version>${mybatis-spring-boot.version}</version>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId>
      <version>${jjwt.version}</version>
    </dependency>
    <!-- jjwt-impl / jjwt-jackson 同为 ${jjwt.version} -->
  </dependencies>
</dependencyManagement>
```

**`mysql-connector-j` 与三个 starter 不写版本**——由 `spring-boot-starter-parent` 管理。

**Step 2: 建表脚本 `db/V1__init_user.sql`**

按设计 §2 建 `t_user` / `t_role` / `t_user_role`，库名 `wingtisky_forum`。
**另建一个测试库 `wingtisky_forum_test`**（ADR-0007：集成测试连独立 test 库，不污染开发数据）。

- [ ] **Step 3: 验证 MySQL 驱动与 8.0.41 服务端兼容（**高危区，必须实测**）**

Boot 3.5.16 管理的 `mysql-connector-j` 是 **9.7.0**，而本机服务端是 **8.0.41**。**这个组合必须实际连一次才算验证**（与 Redisson×Redis 5 同类问题）。

```bash
mysql -u root -p < db/V1__init_user.sql
mysql -u root -p -e "SHOW TABLES;" wingtisky_forum
# 启动应用，看 HikariCP 是否成功建连、MyBatis 是否报驱动异常
```

**若不兼容**：在父 POM 里显式固定 `mysql-connector-j` 到 8.0.x 并记录到 ADR-0001 的修订。

**Step 4: `application.yml`**——敏感项走环境变量

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/wingtisky_forum?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  data:
    redis:
      host: 127.0.0.1
      port: 6379
mybatis:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true
```

**Step 5: `RedisKey`**——Key 命名规范收在 `wt-infra`（**机制**）；而"刷新令牌活多久"是**策略**，留在 `forum-user`。这条线是 architecture.md §4.3 的要求，不要混。

**Step 6: 提交**（拆 2-3 条：依赖与配置 / 建表脚本 / Redis Key 规范）

---

## Task 3: 用户领域（实体 / Mapper / Service）

**Files:** `entity/User.java`、`entity/Role.java`、`mapper/*.java`、`resources/mapper/*.xml`、`service/UserService.java`

- [ ] **Step 0: 向用户确认**

**要点：**

- **密码用 `BCryptPasswordEncoder`**（Spring Security 自带）。**绝不明文、绝不进日志、绝不返回给前端**
- **Mapper 用手写 SQL**（XML），不用注解拼 SQL——M6 的深翻页亮点要求手写 SQL 能力，这里就开始
- **Service 不返回实体给 Controller**，返回 DTO（见 Task 7）
- 注册时 `username` 唯一性：**靠数据库唯一索引兜底，不要只靠"先查再插"**——并发下"查完没占用"和"插入"之间有窗口。要捕获 `DuplicateKeyException` 并转成 `USERNAME_EXISTS`

- [ ] **Step 3: 验证**（单测 + 真实 DB 的集成测试）

```bash
mvn -pl forum/forum-user -am test -Dtest='UserServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```

**集成测试打 `@Tag("integration")`**，只在本地跑（ADR-0007），CI 不执行。

---

## Task 4: 认证（Spring Security 6 + JWT 双令牌）

**Files:** `security/JwtTokenProvider.java`、`security/JwtAuthenticationFilter.java`、`security/SecurityConfig.java`、`service/AuthService.java`、`controller/AuthController.java`

- [ ] **Step 0: 向用户确认**（含"JWT 用 jjwt 还是 Spring Security 自带"的最终确认）

**⚠️ 高危区**：Spring Security 6 的写法与网上大量 Boot 2 教程**完全不同**。动手前先读官方文档或本地 jar 源码，重点：

- `WebSecurityConfigurerAdapter` **已被删除**，必须用 `SecurityFilterChain` Bean
- `authorizeHttpRequests` 取代了 `authorizeRequests`
- `csrf(csrf -> csrf.disable())` 的 lambda DSL（旧的无参 `csrf().disable()` 写法已废弃）
- 无状态配置：`sessionManagement(s -> s.sessionCreationPolicy(STATELESS))`

**关键实现点：**

1. **`JwtTokenProvider`**：签发 access（30 分钟）与 refresh（7 天）；载荷含 `sub`(userId) / `username` / `roles` / `jti`（设计 §3.3）
2. **refresh 存 Redis**：key `wt:auth:refresh:{userId}`，值 `jti`。注销 = 删这个 key
3. **`JwtAuthenticationFilter`**：从 `Authorization: Bearer xxx` 取 token → 验签 → 从 `roles` 构造 `GrantedAuthority` → 塞进 `SecurityContext`。**全程不查库不查缓存**（这正是 ADR-0010 拆分服务能简化的原因）
4. **白名单**：`/api/auth/login`、`/api/auth/register`、`/api/auth/refresh`、`GET /api/users/{id}` 放行；其余需认证
5. **`JWT_SECRET` 走环境变量**，且 **HS256 的密钥长度必须 ≥ 256 位**——jjwt 会直接拒绝短密钥，这是常见坑

- [ ] **Step 5: 验证**（端到端，真实链路）

```bash
# 1. 注册 → 2. 登录拿双令牌 → 3. 带 access 访问受保护接口（应 200）
# 4. 不带 token 访问（应 401）→ 5. 用 refresh 换新 access（应成功）
# 6. 注销 → 7. 再用那个 refresh 换（应失败，证明可撤销）
```

**第 6、7 步是"注销真的有效"的证据**，不能省。

---

## Task 5: 鉴权（RBAC + 资源归属）

**Files:** `security/AuthzService.java`、`security/SecurityConfig.java`（开启方法级安全）

- [ ] **Step 0: 向用户确认**

**要点：**

- `@EnableMethodSecurity` 开启 `@PreAuthorize`
- `AuthzService`（Bean 名 `authz`）提供 `isOwner(Long userId)`：从 `SecurityContext` 取当前用户，与目标资源的所有者比对
- **为什么收进一个 Bean**（设计 §4.2）：散在各 Service 里手写 `if` 的失败模式是**静默的**——漏写一个不会有任何报错

- [ ] **Step 4: 验证（**闸门之一，必须含反面对照**）**

```bash
# A 登录 → 改 B 的资料  → 期望 403   ← 反例
# A 登录 → 改 A 自己的资料 → 期望 200  ← 正例（**没有这条，反例证明不了任何事**）
```

---

## Task 6: 亮点 5 —— Redis ZSet 滑动窗口限流

**Files:** `wt-infra/.../redis/RateLimitScript.java`、`forum-user/.../ratelimit/RateLimit.java`、`ratelimit/RateLimitInterceptor.java`

- [ ] **Step 0: 向用户确认**

**要点：**

1. **Lua 脚本一次判定**（设计 §5.2）：清窗口外 → 数窗口内 → 未超限则记录，**必须原子**。分开写会漏（这与秒杀扣库存是同一类问题）
2. **按接口分类的降级策略**（设计 §5.3）：普通接口 fail-open；登录/注册降级到**本地内存**
3. **阈值与策略可配置**，不硬编码
4. **任何降级都要记日志**——fail-open 记 WARN/ERROR，这是"限流正在失效"的唯一信号
5. **拦截位置**：MVC `HandlerInterceptor`。**M9 才前移到网关**

- [ ] **Step 5: 验证（**闸门之一，必须含反面对照**）**

```bash
# 循环打同一接口超过阈值 → 断言 429，且响应体是统一格式的 TOO_MANY_REQUESTS
# 反面对照：阈值内全部正常返回 200
# 降级对照：停掉 Redis → 普通接口应放行(fail-open)；登录接口应走本地内存仍能限住
```

> **降级对照是本任务最有价值的一条**：它是"fail-open 与本地兜底真的按接口生效"的唯一证据。停 Redis 的操作要按端口找 PID，别用 `taskkill /IM java.exe`。

---

## Task 7: 接口层（Controller / DTO / 参数校验）

**Files:** `controller/AuthController.java`、`controller/UserController.java`、`dto/*`

- [ ] **Step 0: 向用户确认**

**要点：**
- 按设计 §7 的接口清单实现
- **请求与响应都用 DTO，绝不直接返回实体**——实体会漏字段（`password`），且表结构一改接口就变
- 参数校验用 `spring-boot-starter-validation`（`@Valid` + `@NotBlank` 等）
- 接口返回统一 `Result<T>`

- [ ] **Step 4: 提交 `.http` 脚本**（放到 `scripts/` 或 `docs/`，供手工端到端复用），并**实际跑通全流程**

---

## Task 8: 测试

**Files:** `forum/forum-user/src/test/java/**`

- [ ] **Step 0: 向用户确认**

**分层（spec §7）：**

| 层 | 内容 | 跑在哪 |
|---|---|---|
| 单元测试 | 限流算法（边界：刚满、超一个、窗口滑动后恢复）、JWT 签发解析、密码哈希 | **CI 也跑** |
| 集成测试 | 注册→登录→鉴权全链路，连真实 MySQL + Redis | **仅本地**（`@Tag("integration")`，ADR-0007） |

> **限流算法的边界用例是重点**：设计 §5.1 说选 ZSet 就是为了避免固定窗口的边界问题——**那就要有测试证明边界确实没问题**。否则"我选了更好的方案"只是说法。

---

## Task 9: 验收与收尾

- [ ] **Step 1: 两条闸门实测（含反面对照）**

```bash
# 闸门① 限流实测 429   —— 正面 + 反面（阈值内正常）
# 闸门② 越权访问实测被拒 —— 正面（A 改 B → 403）+ 反面（A 改 A → 200）
```

- [ ] **Step 2: 写 ADR-0012 / 0013 / 0014** 并更新 `docs/02-decisions/README.md` 索引
- [ ] **Step 3: 回填 spec §12**——持久层选型的决策时点由「M2」改为「M1」，并注明原因（M1 就要写用户表，排到 M2 是排期遗漏）
- [ ] **Step 4: 更新 `STATUS.md` 与 `docs/04-log/`**；**用实测速度校准 M2 之后的工期**
- [ ] **Step 5: 打 `m1-done` tag → 开 PR → 合并**（走分支保护，等 CI 绿）

---

## Self-Review

**1. 设计覆盖** — 逐个设计章节确认有任务承接：

| 设计章节 | 承接任务 |
|---|---|
| §2 库表 | Task 2 |
| §3 认证（双令牌 / 注销） | Task 4 + ADR-0013 |
| §4 鉴权（RBAC / 资源归属） | Task 5 |
| §5 亮点 5（ZSet / 降级） | Task 6 + ADR-0014 |
| §6 公共设施（响应体 / 错误码 / 异常 / traceId / 合规） | Task 1 |
| §7 接口清单 | Task 7 |
| §8 持久层选型 | Task 2 + ADR-0012 |
| §9 验收方法 | Task 5 / 6 / 9 |
| §10 待确认的 5 个决策点 | 已并入设计定稿；Task 2/4 的 Step 0 会再次确认依赖问题 |

**2. 闸门覆盖**：限流 429（Task 6/9）、越权被拒（Task 5/9）——**两条都要求反面对照**。

**3. 未覆盖 / 待决**：

| 项 | 说明 |
|---|---|
| Redis 客户端选型（Lettuce vs 只用 Redisson） | 见「新增依赖」问题一，**需用户确认** |
| JWT 库选型（jjwt vs Spring Security 自带） | 见「新增依赖」问题二，**需用户确认** |
| `wt-common` 是否引入 web | Task 1 Step 0，**需用户确认** |
| mysql-connector-j 9.7.0 × MySQL 8.0.41 | Task 2 Step 3 **必须实测**，与 Redisson×Redis 5 同类 |
