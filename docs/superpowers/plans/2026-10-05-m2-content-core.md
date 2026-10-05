# M2 · 内容域核心 + 前端 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `forum-content` 与 `wt-domain` 两个空壳真正立起来——帖子、评论、标签、点赞收藏、后台治理，
配一个**最小但排版达标**的 Vue3 前端。M2 是 M3–M6 四个亮点的**数据基础**：
缓存要缓存帖子、Kafka 要异步写评论与点赞、ES 要索引帖子正文、深翻页要有一张能塞 10w 行的表。

**工期：** 4 周（spec §6.2，**预估值**）。M1 因为全程 AI 连续执行（1 个工作日），
对 M2 的参考价值有限——**M2 的实测工期要单独记录**，别拿 M1 那个数字外推。

**Architecture:** 仍是单进程逻辑态（architecture.md §3.1）。业务代码落 `forum/forum-content`；
**跨域契约第一次落进 `wt-domain`**（ADR-0015）；`wt-common` 加内容域错误码段；
`frontend/` 放 Vue3 工程。

**设计依据：** `docs/03-design/m2-content-core.md`（**先于本计划定稿，本计划不重复其推理**）。
本计划只回答"怎么把它做出来"。相关 ADR：0015 / 0016 / 0017 / 0018。

**Tech Stack:** Java 17 · Spring Boot 3.5.16 · Spring Security 6 · MyBatis · MySQL 8.0.41 · Redis 5.0.14.1 · JUnit 5 · ArchUnit 1.5.0 · Vue3 + Vite + Element Plus

---

## Global Constraints

以下为项目级硬约束，**每个任务都隐式包含**（与 M1 计划相同，未变）：

- **Java 17**。**禁止 `javax.*`**，一律 `jakarta.*`。
- **依赖防幻觉（spec §9 闸门 3）**：新增依赖必须真实存在且可解析。**M2 的后端不新增依赖**；
  前端依赖（Vue3 / Vite / Element Plus / vue-router / axios）要**逐个查 npm registry**，
  不接受"我记得版本是…"。
- **证据制（闸门 1）**：任何"已完成"必须附**可复现命令 + 真实输出**。
- **反验证剧场（闸门 2）**：M2 的四条反面对照（未登录发帖 401 / 越权 403 vs 自己 200 /
  重复点赞幂等 / 用户名限流）**必须全部有正反两侧**。单边数据不算证据。
- **用户确认闸门（优先级高于任何执行技能）**：**每个 Task 动手前**先向用户说清
  ① 这步做什么 ② 为什么现在做、不做会怎样 ③ 有哪几种做法、建议哪种、代价是什么，
  **等回应才动手**。`subagent-driven-development` 默认"任务之间不停下确认"——**该默认在本项目失效**。
- **提交拆分**：一个 Task 通常 **3-8 个提交**，按 `契约 → 领域 → 装配 → 测试` 分层。
  **修复独立成条**，文档与代码不混提。每一刀都必须可编译。
- **链路讲解（`collaboration.md §2.6`）**：**一条链路做完后、下一条开始前**，
  派子 agent 产出一份"从零能读懂"的讲解，放进 `docs/05-interview/`。
  **子 agent 直接写文件、只回一行回执**；只解释代码实际怎么走，
  决策理由写成引用（如 `见 ADR-0016`），**不复述**。改字段 / 修 bug / 纯文档改动不触发。
- **敏感信息绝不入库**：`.env` 不进仓库；前端**不得**把任何密钥写进代码（前端本来也不需要）。
- **中间件**：M2 只需要 **MySQL + Redis**。
  **⚠️ 开工前先起 Redis**（`cd /d/aaaSoftware/redis && ./redis-server.exe &`）：
  M1 之后认证与限流都依赖它，**不开则登录直接失败**。ES 与 Kafka 仍然不需要。
- **停止进程一律按端口找 PID**（`netstat -ano | grep ':8080 '`），**禁止 `taskkill /IM java.exe`**。
- **既有命令坑**（M1 实测，勿照抄记忆）：
  1. `-Dtest=XxxTest` 配 `-am` 时加 **`-Dsurefire.failIfNoSpecifiedTests=false`**
  2. `mvn clean compile` **不产 jar**；要用 jar 必须先 `package`
  3. `mvn -pl app -am spring-boot:run` **不可用** → 先 `package -DskipTests`，再 `java -jar`
  4. 建表脚本**开发库与测试库都要执行**，只跑一个会让集成测试因缺表失败

---

## 新增依赖

**后端：无。** M2 用的 MyBatis、spring-boot-starter-web、validation 都已在 M1 引入。
**若某一步发现需要新依赖，先停下问用户**（白名单规矩）。

**前端**（白名单内的 Vue3 生态，逐个核实后填入确切版本）：

| 包 | 用途 | 核实方式 |
|---|---|---|
| `vue` | 框架 | `npm view vue version` |
| `vite` | 构建 | `npm view vite version` |
| `element-plus` | UI 组件库（ADR-0003 已定） | `npm view element-plus version` |
| `vue-router` | 路由 | `npm view vue-router version` |
| `axios` | HTTP | `npm view axios version` |

**明确不引入**：Pinia（登录态用 `localStorage` + 组合式函数足够）、任何 SSR 方案、
组件库的二次封装。理由见设计稿 §6.2。

---

## File Structure

```
WingtiskyForum/
├─ wt-domain/src/main/java/com/wingtisky/forum/domain/user/
│  ├─ UserBrief.java                    跨域只读投影（id / nickname / avatar）
│  └─ UserQueryService.java             契约接口（含批量方法）
├─ wt-common/…/common/result/ErrorCode.java     ★ 改：加 A02xx 内容域段
├─ db/V2__init_content.sql               ★ 内容域六张表
├─ forum/forum-content/src/main/java/com/wingtisky/forum/forum/content/
│  ├─ entity/       Post · Comment · Tag · PostTag · PostLike · PostCollect
│  ├─ mapper/       PostMapper · CommentMapper · TagMapper · PostLikeMapper · PostCollectMapper
│  ├─ service/      PostService · CommentService · TagService
│  ├─ controller/   PostController · CommentController · TagController · AdminPostController
│  └─ dto/          请求 / 响应 DTO（绝不返回 entity）
├─ forum/forum-content/src/main/resources/mapper/     MyBatis XML（手写 SQL）
├─ forum/forum-content/src/test/java/…               单测 + 集成测试
├─ forum/forum-user/…/service/UserQueryServiceImpl.java    ★ 实现 wt-domain 契约
├─ forum/forum-user/…/service/AuthService.java             ★ 改：加用户名维度限流
├─ forum/forum-user/…/security/SecurityConfig.java         ★ 改：公开接口加进 PUBLIC_ENDPOINTS
├─ app/src/main/resources/application.yml                  ★ 改：限流规则补 M2 的写接口
├─ docs/06-runbook/api-smoke.http                          ★ 改：补 M2 的冒烟用例
└─ frontend/                                               Vue3 工程（M0 已规划位置，M2 才建）
```

---

## Task 1: 跨模块契约（`wt-domain` 的第一次落地）

**Files:**
- Create: `wt-domain/src/main/java/com/wingtisky/forum/domain/user/UserBrief.java`
- Create: `wt-domain/src/main/java/com/wingtisky/forum/domain/user/UserQueryService.java`
- Create: `forum/forum-user/src/main/java/com/wingtisky/forum/forum/user/service/UserQueryServiceImpl.java`
- Modify: `forum/forum-user/src/main/java/com/wingtisky/forum/forum/user/mapper/UserMapper.java`（**新增批量查询**）
- Modify: `forum/forum-user/src/main/resources/mapper/UserMapper.xml`
- Create: `forum/forum-user/src/test/java/com/wingtisky/forum/forum/user/service/UserQueryServiceImplTest.java`
- Create: `docs/05-interview/01-跨域契约.md`（链路讲解，见 `collaboration.md §2.6`）

> **⚠️ 2026-10-05 更正（动手前读代码读出来的两处）**：
> 1. 初稿写"给 `forum-user` / `forum-content` 的 pom 加 `wt-domain` 依赖"——**这是多余的**。
>    两个 pom 在 M0 建骨架时就**已经声明了** `wt-domain`（`forum-user/pom.xml:22-25`、
>    `forum-content/pom.xml:22-25`）。**这一步不动任何 POM。**
> 2. 初稿漏了一条：`UserMapper` 只有 `selectById(Long)`，**没有批量方法**，
>    而 `findBriefs` 必须批量（否则列表页就是 N+1）。所以要新增 mapper 方法 + XML。

**Interfaces:**
- Produces: `UserBrief(Long id, String nickname, String avatar)`——**用 `record`**（不可变，语义就是"只读投影"）。
  **但它不由 MyBatis 直接映射**（record 没有无参构造，映射要写 `<constructor>` resultMap，
  是个已知摩擦点）。做法：mapper 返回 `User`（**SQL 只 select id / nickname / avatar 三列**），
  Service 里 `new UserBrief(...)` 转一次。这样既保住不可变的语义，又绕开映射坑，转换逻辑还能单测。
  ⚠️ 那个"只填三个字段的 `User`"是**部分填充对象**，必须在方法名（`selectBriefsByIds`）与
  注释里写清楚"**不要当完整的 User 用**"——否则下一个人会拿它去读 `password`（会是 null）
- Produces: `UserQueryService`，两个方法：
  - `Map<Long, UserBrief> findBriefs(Collection<Long> userIds)` —— **批量，列表页必须用它**
  - `Optional<UserBrief> findBrief(Long userId)` —— 单个，详情页用
- Consumes: `forum-user` 的 `UserMapper` / `UserService`

**为什么先做这一步**：它是 `forum-content` 所有 Service 的依赖，
且 ArchUnit 会对依赖方向做硬检查——**先把边界立对，后面才不会为了编过而偷偷加依赖**。

- [ ] 确认 `app/pom.xml` 已依赖 `forum-content`（M0 建骨架时应有；没有则补）
- [ ] `UserBrief` 用 `record`：它是**投影不是实体**，不该有 setter 与可变状态
- [ ] `UserQueryServiceImpl` 上加 `@Service`；`findBriefs` 传空集合时**直接返回空 Map**，
      不要拼出 `IN ()` 这种非法 SQL（容易漏，且只在空列表时炸）
- [ ] `findBriefs` 的返回 Map **对查不到的 id 不补位**——调用方自己用 `getOrDefault`
      或显示"已注销用户"；补位会让"用户不存在"与"用户叫 null"两种情况混在一起

**验证：**
```bash
mvn -q -pl wt-domain,forum/forum-user,forum/forum-content -am test
# 期望：ArchitectureTest 全绿——尤其"规则2：三个业务域两两不得互相依赖"
#       forum-content 只 import com.wingtisky.forum.domain.*，不 import ...forum.user.*
grep -rn "forum.forum.user" forum/forum-content/src/main/java/ ; echo "退出码=$?"
# 期望：无匹配（退出码 1）
```

---

## Task 2: 库表脚本 `V2__init_content.sql`

**Files:**
- Create: `db/V2__init_content.sql`
- Modify: `docs/06-runbook/local-setup.md`（补一句 V2 的执行命令）

**表结构以设计稿 §2 为准**（`t_post` / `t_tag` / `t_post_tag` / `t_comment` /
`t_post_like` / `t_post_collect`），**不要在这里重新设计**。

- [ ] 沿用 `V1__init_user.sql` 的全部约定：`CREATE TABLE IF NOT EXISTS`、`utf8mb4`、
      `create_time`/`update_time` 默认值、字段带 `COMMENT`、**不建外键**
- [ ] `t_post_like` / `t_post_collect` **不带 `deleted`**——这是设计稿 §2.5 写明的例外，
      在脚本里用注释说明为什么（否则下一个人会以为是漏了）
- [ ] 索引按设计稿 §2 建全，尤其：
      `idx_status_top_create (status, top_flag, create_time)`、
      `idx_post_root (post_id, root_id, id)`、两张关联表的反向索引
- [ ] 文件头注释写明**开发库与测试库都要执行**

**验证：**
```bash
mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum      < db/V2__init_content.sql
mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum_test < db/V2__init_content.sql
# 期望：两次都无报错。然后核验表与索引真的建了：
mysql -u wingtisky -p -e "SHOW INDEX FROM t_comment" wingtisky_forum
# 期望：能看到 idx_post_root，且列顺序是 post_id, root_id, id
```
> ⚠️ `--default-character-set=utf8mb4` **不能省**（Windows 上默认 gbk，会把中文写坏且不报错）。

---

## Task 3: 帖子领域（实体 / Mapper / Service）

**Files:**
- Create: `entity/Post.java`、`mapper/PostMapper.java`、`service/PostService.java`
- Create: `forum-content/src/main/resources/mapper/PostMapper.xml`

**Interfaces:**
- Produces: `PostService.create(...)` / `getById(...)` / `update(...)` / `delete(...)` /
  `page(query)`（返回帖子 + 作者信息的组合结果）
- Consumes: `UserQueryService.findBriefs(...)`（**批量**）

- [ ] **列表查询的 SQL 必须显式列出字段，禁止 `SELECT *`**（设计稿 §2.6）——
      这是 M6 深翻页亮点的前提，代码评审时重点看这条
- [ ] 列表页组装作者信息：收集本页所有 `author_id` → **一次** `findBriefs` → 回填。
      **禁止在循环里查询**
- [ ] 列表排序按设计稿 §10 的 ⑩：
      `latest` → `ORDER BY top_flag DESC, create_time DESC`；
      `hot` → `ORDER BY top_flag DESC, (comment_count*3 + like_count*2 + collect_count) DESC`。
      **热度公式上方写明"这是初始公式，M6 用真实数据与 EXPLAIN 校准"**
- [ ] `delete` 是**逻辑删除**（`deleted = 1`），与 `status` 分开（设计稿 §2.1）
- [ ] 详情查询同一处做 `view_count + 1`，并**注释标注"这是 M3 要换成 Redis 计数的地方"**

**验证：**
```bash
mvn -q -pl forum/forum-content -am test
# 期望：单测通过。至少覆盖：逻辑删除后查不到、分页边界（page=0 / size 过大被夹紧）
```

---

## Task 4: 帖子接口层（Controller / DTO / 参数校验）

**Files:**
- Create: `controller/PostController.java`、`dto/` 下的请求与响应 DTO
- Modify: `SecurityConfig`（公开接口加进 `PUBLIC_ENDPOINTS`）
- Modify: `ErrorCode`（加 A02xx 段）
- Modify: `application.yml`（限流规则补 M2 接口）

**Interfaces:**
- Produces: `POST /api/posts`、`GET /api/posts`、`GET /api/posts/{id}`、
  `PATCH /api/posts/{id}`、`DELETE /api/posts/{id}`、`GET /api/users/{id}/posts`

- [ ] **DTO 分层**（M1 约定）：请求 DTO 收参、响应 DTO 出参，**entity 不出 Service 层**
- [ ] 编辑 / 删除走 `@PreAuthorize("@authz.isOwner(#id)")`（M1 设计 §4.2 的归属校验 Bean）
- [ ] **公开接口必须显式加进 `PUBLIC_ENDPOINTS`**：`GET /api/posts`、`GET /api/posts/*`、
      `GET /api/tags`、`GET /api/users/*/posts`。漏一个的表现是匿名访问 401，容易发现
- [ ] 分页参数做**上限夹紧**（如 `size` 最大 50）——不加的话 `size=100000` 就是一个免费的 DoS
- [ ] 搜索先走 `LIKE`，并在代码注释里标注**"M5 会用 ES 替换掉这里"**

**验证：**（用 `api-smoke.http` 或 curl 按序跑）
```bash
# 1) 登录拿 access token  → 2) 发帖  → 期望 200，返回 id
# 3) 不带 token 发帖      → 期望 401  ← 反面对照
# 4) GET /api/posts       → 期望 200 且不需要 token  ← 公开接口
# 5) A 改 B 的帖          → 期望 403
# 6) A 改 A 自己的帖      → 期望 200  ← 没有这条，5 证明不了任何事
```

---

## Task 5: 评论（两级模型）

**Files:**
- Create: `entity/Comment.java`、`mapper/CommentMapper.java`、`service/CommentService.java`
- Create: `controller/CommentController.java`、`resources/mapper/CommentMapper.xml`
- Modify: `PostMapper.xml`（`comment_count` 的维护）

**Interfaces:**
- Produces: `POST /api/posts/{id}/comments`（带 `parentId`）、`GET /api/posts/{id}/comments`、
  `DELETE /api/comments/{id}`

- [ ] **`root_id` 的取值是这一步最容易写错的地方**（ADR-0018 已点名）：
      回复顶层评论时 `root_id` 取**被回复评论的 id**；
      回复一条回复时 `root_id` 取**父评论的 `root_id`**（不是父评论的 id）。
      **这条必须有单元测试**，不能靠"写的时候注意"——写错的表现是"评论跑到别的楼里"
- [ ] 不允许回复一条"回复"以外的层级混乱：`parentId` 指向的评论必须属于**同一个 `post_id`**
      （跨帖回复要拒绝，否则会出现挂错帖的评论）
- [ ] 发评论 / 删评论与 `t_post.comment_count` 的更新**放在同一事务**
- [ ] 评论列表按顶层分页（`root_id = 0`），每个顶层评论**批量**取它的回复

**验证：**
```bash
mvn -q -pl forum/forum-content -am test
# 单元测试必须包含：回复"回复"时 root_id 等于祖父的 root_id（正面）+ 不等于父评论 id（反面）
```

---

## Task 6: 标签

**Files:**
- Create: `entity/Tag.java`、`entity/PostTag.java`、`mapper/TagMapper.java`、
  `service/TagService.java`、`controller/TagController.java`

**Interfaces:**
- Produces: `GET /api/tags`；发帖时按名字取用/创建标签

- [ ] **并发创建同一个标签**：靠 `name` 的唯一索引兜底——
      捕获唯一键冲突后**回头重查**，不要"先查后插"（并发下会炸）
- [ ] 标签规范化：`trim`、去重、单帖最多 5 个（超了返回 `A0203`）；
      **不做小写化**——去重靠 `utf8mb4` 默认排序规则的大小写不敏感（设计稿 §2.3）
- [ ] `t_tag.post_count` 的维护与发帖 / 删帖同事务

**验证：**
```bash
mysql -u wingtisky -p -e "SELECT name, post_count FROM t_tag" wingtisky_forum
# 期望：发 2 帖带同一标签 → 该标签 post_count = 2，且只有一行（不是两行同名）
```

---

## Task 7: 点赞与收藏（幂等 + 计数维护）

**Files:**
- Create: `entity/PostLike.java`、`entity/PostCollect.java`、
  `mapper/PostLikeMapper.java`、`mapper/PostCollectMapper.java`
- Modify: `PostService` 或新建 `InteractionService`（点赞收藏的业务）

**Interfaces:**
- Produces: `PUT /api/posts/{id}/like`、`DELETE /api/posts/{id}/like`、
  `PUT /api/posts/{id}/collect`、`DELETE /api/posts/{id}/collect`

- [ ] 幂等靠**联合主键冲突**（ADR-0017），不是"先查再插"
- [ ] 计数更新用单语句 `UPDATE t_post SET like_count = like_count + 1 WHERE id = ?`，
      **不要读出来加一写回去**（并发丢更新）
- [ ] 取消时计数**不得为负**：`like_count = GREATEST(like_count - 1, 0)`
- [ ] 点赞行与计数同事务

**验证：**（这条是设计稿 §8 的反面对照之一）
```bash
# 连点两次 PUT /api/posts/{id}/like
# 期望：两次都 200，且 t_post.like_count 只加 1
mysql -u wingtisky -p -e "SELECT like_count FROM t_post WHERE id = {id}" wingtisky_forum
mysql -u wingtisky -p -e "SELECT COUNT(*) FROM t_post_like WHERE post_id = {id}" wingtisky_forum
# 期望：两者相等，且都是 1
```

---

## Task 8: 后台治理接口

**Files:**
- Create: `controller/AdminPostController.java`
- Modify: `application.yml`（如需单独的限流规则）

**Interfaces:**
- Produces: `PATCH /api/admin/posts/{id}`（body：`topFlag` / `featuredFlag` / `status`）

- [ ] 权限用 `@PreAuthorize("hasAnyRole('MODERATOR','ADMIN')")`
- [ ] **只做接口，不做后台页面**（设计稿 §10 的 ⑨）
- [ ] 反面对照必测：**普通用户调该接口 → 403**

**验证：**
```bash
# 普通用户 token 调 PATCH /api/admin/posts/{id} → 期望 403  ← 反面对照
# ADMIN token 调同一个接口                    → 期望 200  ← 正面
mysql -u wingtisky -p -e "SELECT top_flag, featured_flag, status FROM t_post WHERE id={id}" wingtisky_forum
# 期望：字段确实被改了（不能只看 HTTP 200——它可能什么都没干）
```

---

## Task 9: 补 M1 遗留 —— 限流的用户名维度

**Files:**
- Modify: `AuthService.login(...)`（加限流）
- Modify: `RateLimitProperties.java`（加 `loginUsername` 配置段）
- Modify: `application.yml`

**设计依据：** 设计稿 §5。**位置选在 `AuthService.login` 而不是拦截器**——
拦截器拿不到请求体里的用户名（M1 设计 §5.4 已论证过）。

- [ ] 计数放在**密码校验之前**（否则撞库已经打到 BCrypt 了）
- [ ] 复用 `SlidingWindowRateLimiter`；Key 用 `RedisKey.rateLimit("username", username, "/api/auth/login")`
- [ ] 降级用 `LocalRateLimiter`（与登录的 IP 维度同策略）
- [ ] **在代码注释里写明"为什么这段限流不在统一拦截器里"**——
      否则下一个人会以为它是随手写的，然后把它挪回去，一挪就坏

**验证：**（设计稿 §8 的最后一条反面对照）
```bash
# 同一用户名连续登录 6 次（密码故意错）
# 期望：前 5 次 401，第 6 次起 429
# 反面对照：换一个用户名再打 → 仍是 401，不是 429（证明维度独立，不是把 IP 也一起限了）
```

---

## Task 10: 前端工程 + 4 个页面

**Files:**
- Create: `frontend/`（Vite 工程）、`vite.config.js`（dev proxy）、`src/` 下的页面与 `api/` 封装

**设计要求见设计稿 §6**（页面清单与质量底线）。四个页面：登录/注册、帖子列表、帖子详情、发帖。

- [ ] 依赖逐个 `npm view <pkg> version` 核实后再写进 `package.json`（闸门 3 的前端版本）
- [ ] **Vite dev proxy 把 `/api` 转到 `localhost:8080`**，**不要在后端开 CORS**（设计稿 §6.3）
- [ ] axios 拦截器统一处理 `Result.code`：`401` 跳登录、其余显示后端 `message`
- [ ] 四个页面共用一个布局（顶栏 + 居中容器），**不各写各的间距**
- [ ] 列表页必须有：**加载态、空状态、分页控件**——"没有内容时一片空白"不算达标
- [ ] `frontend/` 加 `.gitignore`（`node_modules` 绝不入库）

**验证：**
```bash
cd frontend && npm run dev
# 浏览器走一遍：注册/登录 → 列表（含空状态）→ 发帖 → 详情 → 评论 → 点赞
# 期望：① 全流程无报错 ② 截图 4 个页面 + 空状态/加载态各一张
```
> ⚠️ 前端**必须先起 Redis 与后端**才能联调。

---

## Task 11: 闸门验收、文档回填与收尾

**Files:**
- Modify: `docs/06-runbook/api-smoke.http`（补 M2 用例）
- Modify: `README.md`（补「本地运行」一节）
- Modify: `docs/STATUS.md`（进度 / 证据等级 / 下一步 / 阻塞）
- Create: `docs/04-log/03-M2-内容域核心与前端.md`

- [ ] `api-smoke.http` 按设计稿 §8 的表补齐（含四条反面对照）
- [ ] `README.md` 补「本地运行」——M1 结束时触发条件就已满足，一直欠着（M1 设计稿记着这条）
- [ ] `docs/04-log/` 写 M2 执行记录（结构见 `collaboration.md §6`）
- [ ] **M2 工期实测单独记录**，不与 M1 那个"1 个工作日"合并
- [ ] 打 tag `m2-done`，走 PR → **Merge commit（`--no-ff`）**
- [ ] `bash tools/check-doc-refs.sh` 必须**转绿**——
      它在 M2 期间因 `UserQueryService` 尚未存在而报 1 处漂移（设计稿提交时已注明），
      **Task 1 完成后这条就该消失了**。若仍报，说明契约类名与文档不一致

**验证：**
```bash
bash tools/check-doc-refs.sh   # 期望：通过（M2 期间那处漂移已随 Task 1 消失）
bash tools/check-deps.sh       # 期望：通过
mvn -B clean test               # 期望：全绿
```

---

## Self-Review

**1. 覆盖度**：设计稿 §1 的「做什么」逐项对上——
帖子（T3/T4）、评论（T5）、标签（T6）、点赞收藏（T7）、后台（T8）、
补 M1 限流（T9）、前端（T10）；契约（T1）与库表（T2）是它们的前置。

**2. 闸门**：`发帖 → 评论 → 列表` 落在 T4/T5 的验证 + T10 的浏览器走查（真实 HTTP）。
四条反面对照分别落在 T4（401/403+200）、T7（幂等）、T9（限流），
**没有一条只有正面证据**。

**3. 已知的临时实现**（M2 会欠下、后续里程碑要还的债，均已在代码注释中标注）：
| 临时实现 | 谁来还 | 在哪标注 |
|---|---|---|
| 列表搜索用 `LIKE` | M5（ES） | `PostService` 注释 |
| 浏览量直接 `UPDATE +1` | M3（Redis 计数） | `PostService` 注释 |
| `hot` 排序的系数是拍出来的 | M6（用数据与 EXPLAIN 校准） | `PostMapper.xml` 注释 |
| 计数的短暂不一致 | M3（缓存一致性） | ADR-0016 后果一节 |

**4. 每步的 Step 0 闸门**：本计划的每个 Task **动手前都要先向用户复述三件事**——
计划写完了不等于可以一路做下去。
