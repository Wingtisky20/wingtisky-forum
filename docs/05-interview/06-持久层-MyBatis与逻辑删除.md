# 链路讲解 · 持久层：MyBatis 与逻辑删除

> 对着 M1 的真实代码讲一遍：一次写请求怎么落到 `t_user`。
> 只讲代码实际怎么走；设计上的"为什么"指向已有文档，不在这里复述第二遍。

---

## 一句话

Service 调一个**没有实现类的接口**，MyBatis 拿它当钥匙去 XML 里找到手写的 SQL，拼成一条带占位符的 JDBC 语句发给 MySQL；唯一性、逻辑删除、字段映射这些容易出错的地方，全都在 SQL 和表结构里**显式写死**，不交给框架猜。

---

## 数据怎么走

以注册为例，一次 `POST /api/auth/register` 的落库路径：

```
AuthController.register
      │  （DTO 注解已校验过长度/格式）
      ▼
UserService.register            @Transactional —— 整个方法一个事务
      │
      ├─ userMapper.insert(user)                    ← 接口方法，无实现类
      │        │
      │        ▼
      │   MyBatis：namespace=...UserMapper + id=insert
      │        → 取出 XML 里那条 <insert>
      │        → INSERT INTO t_user (...) VALUES (?, ?, ...)
      │        → JDBC PreparedStatement 发给 MySQL
      │        ← 回填自增主键到 user.id（useGeneratedKeys）
      │
      ├─ roleMapper.selectByCode("USER")             → 取默认角色
      │
      └─ userRoleMapper.insert(user.getId(), roleId) → INSERT IGNORE 进 t_user_role
                                                    ↑ 用的是上一步回填的 id
      提交事务
```

失败时反着往回走：MySQL 报唯一键冲突 → Connector/J 抛 `SQLIntegrityConstraintViolationException` → Spring 的 `SQLExceptionTranslator` 把它翻成 `DuplicateKeyException` → MyBatis 原样上抛 → `UserService` 捕获并转成业务错误码。

这条链上有三个"接口没有实现类"的转折点，下面逐个说。

---

## 逐段讲代码

### 1. 接口 + `@Mapper`，实现由 MyBatis 生成

```java
@Mapper
public interface UserMapper {
    int insert(User user);
    User selectById(Long id);
    User selectByUsername(String username);
    int updateProfile(User user);
    List<String> selectRoleCodesByUserId(Long userId);
}
```

`UserMapper` 只有方法签名，没有任何实现。启动时 MyBatis 扫描 `@Mapper`，为每个接口生成一个动态代理；调用时代理拿**「接口全限定名 + 方法名」**去 XML 里找对应的语句：

```xml
<mapper namespace="com.wingtisky.forum.forum.user.mapper.UserMapper">
    <insert id="insert"> ... </insert>
```

`namespace` 对上接口的全限定名，`id` 对上方法名。两处任何一个拼错，启动时（或首次调用时）会报 `Invalid bound statement (not found)`——这是 MyBatis 最常见的报错，原因几乎总能归到"namespace/id 没对上"或"XML 没被打进 classpath"。后者由配置兜住：

```yaml
mybatis:
  mapper-locations: classpath*:mapper/**/*.xml
```

`classpath*:`（带星号）是"从所有 classpath 位置扫"，不是只扫第一个。M1 只有 forum-user 一个模块的 XML，但 M2 之后多个模块各带自己的 `mapper/*.xml`，不带星号会只加载到其中一份。

### 2. `resultType` 写全限定名

```xml
<sql id="Base_Column_List">
    id, username, password, nickname, avatar, email, status, create_time, update_time, deleted
</sql>

<select id="selectById" resultType="com.wingtisky.forum.forum.user.entity.User">
    SELECT <include refid="Base_Column_List"/>
    FROM t_user
    WHERE id = #{id} AND deleted = 0
</select>
```

`resultType` 这里写的是全限定名，没用短名 `User`。短名要靠 `type-aliases-package` 全局配置才能生效，而那个配置是**全局的、跨模块的**——新增模块时要回头改别处。全限定名没有这个耦合，而且能在仓库里直接 grep 到"谁在用它"。代价只是每行多打几个字。

### 3. `map-underscore-to-camel-case` 必须开

```yaml
mybatis:
  configuration:
    map-underscore-to-camel-case: true
```

上面那条 SQL 返回的列叫 `create_time`，实体里的字段叫 `createTime`。开了这个开关，MyBatis 自动把下划线转成驼峰，两者对上。

**不开会怎样**：`create_time` 在整个 resultType 里找不到同名属性，MyBatis 不会报错，也不会警告——它就**跳过这一列**。结果是 `user.getCreateTime()` 返回 `null`。这类问题的形态是"接口 200、字段是空"，从错误码和堆栈里一点线索都没有。要修的话，每张表每个下划线字段都得手写一份 `<resultMap>` 逐个对应，而**漏掉一行的后果和没开开关完全一样，还是静默为 null**。所以这个开关不是"方便"，是把一类静默错误直接消掉。

### 4. `insert`：主键必须回填

```xml
<insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO t_user (username, password, nickname, avatar, email, status)
    VALUES (#{username}, #{password}, #{nickname}, #{avatar}, #{email}, #{status})
</insert>
```

`useGeneratedKeys="true"` + `keyProperty="id"` 让 MyBatis 从 JDBC 拿回自增主键，set 回入参对象的 `id` 字段。少了这两行，`user.getId()` 就是 `null`——而注册流程紧接着要用这个 id 去插 `t_user_role`：

```java
userRoleMapper.insert(user.getId(), defaultRole.getId());
```

表现为"角色关联表里插进去一条 `user_id = NULL`"（或者直接因为列非空而失败）。这类 bug 的迷惑之处在于：**用户表插得好好的**，坏的是第二步，排查方向容易被带偏。

注意 INSERT 语句里**没有** `create_time` / `update_time` / `deleted`，这三个靠表结构的默认值：

```sql
create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
deleted     TINYINT  NOT NULL DEFAULT 0
```

`update_time` 上的 `ON UPDATE CURRENT_TIMESTAMP` 也是为什么 `updateProfile` 的 SET 子句里不用手写它。

### 5. `updateProfile`：动态 SQL 与"空 SET"陷阱

```xml
<update id="updateProfile">
    UPDATE t_user
    <set>
        <if test="nickname != null">nickname = #{nickname},</if>
        <if test="avatar != null">avatar = #{avatar},</if>
        <if test="email != null">email = #{email},</if>
    </set>
    WHERE id = #{id} AND deleted = 0
</update>
```

`<set>` 会砍掉最后多余的逗号，`<if>` 保证只更新传了的字段——这样"只改昵称"不会把头像顺手清空。

**空 UPDATE 的坑**：如果三个字段全是 `null`，`<set>` 里一个 `<if>` 都不成立，生成的是 `UPDATE t_user WHERE id = ? ...`，这是语法错误。所以校验前移到了 Service：

```java
if (nickname == null && avatar == null && email == null) {
    throw new BizException(ErrorCode.PARAM_INVALID, "至少提供一个要修改的字段");
}
```

把"必填校验"放进 Service 而不是等 SQL 报错，是因为 SQL 那条报错只说"语法错误"，跟"你没传任何字段"看不出任何关系。

另外注意 SET 的**列白名单**：只有 `nickname` / `avatar` / `email`。`username`、`password`、`status` 故意不在这条通用更新里——改密码要单独走一条并校验旧密码，改状态是管理员操作。把它们混进来，迟早有人拿这个"通用更新"去改不该改的东西。

### 6. 每个读都带 `deleted = 0`

```xml
<select id="selectByUsername" resultType="com.wingtisky.forum.forum.user.entity.User">
    SELECT <include refid="Base_Column_List"/>
    FROM t_user
    WHERE username = #{username} AND deleted = 0
</select>
```

逻辑删除的过滤条件**散在每个查询里**，这正是这类设计最典型的 bug 来源：漏写一处，被删的用户就还能被查出来、还能登录。所以这里的选择是"每条 SQL 自己带上 `deleted = 0`"，而不是靠框架的全局拦截（那要引入 Plus 的 `@TableLogic`，见下文）。代价是写新 SQL 时得记得带——这个代价是被明确接受的。

### 7. 不建外键，一致性由业务保证

`db/V1__init_user.sql` 里 `t_user_role` 只有：

```sql
user_id BIGINT NOT NULL COMMENT '用户 ID',
role_id BIGINT NOT NULL COMMENT '角色 ID',
PRIMARY KEY (user_id, role_id),
KEY idx_role_id (role_id)
```

**没有 `FOREIGN KEY ... REFERENCES`**。原因和逻辑删除是一套逻辑：外键会在删除/更新时触发级联检查，和"逻辑删除"（行还在，只是 `deleted = 1`）这套设计配合起来别扭；而在互联网应用里，外键约束带来的写入开销与锁竞争，通常也换不来想要的收益。所以**两个 id 是不是真的存在，由业务保证**——注册流程里角色 ID 来自刚查出来的 `t_role`，用户 ID 来自上一步的回填，都在同一个事务里。

代价要讲清：**数据库不再帮你挡住脏数据**。绕过 Service 直接写 SQL、或者漏了一步事务，就会留下指向不存在用户/角色的关联行。这是拿"数据库层的强保证"换"灵活与性能"的取舍，不是免费的。

`t_user_role` 的联合主键 `(user_id, role_id)` 顺带当了去重手段——同一个用户同一个角色插两次会主键冲突。而额外那条 `KEY idx_role_id (role_id)` 是为了反着查："这个角色下有哪些用户"（管理后台场景）；方向相反，用不上主键索引（联合索引的最左前缀）。

### 8. 注册的第二步用 `INSERT IGNORE`

```xml
<insert id="insert">
    INSERT IGNORE INTO t_user_role (user_id, role_id)
    VALUES (#{userId}, #{roleId})
</insert>
```

重复分配同一角色时静默跳过，而不是报主键冲突。理由：注册是"插用户 → 插角色关联"两步，万一因为重试走到第二次，报错会让整个注册失败，而实际状态其实是对的。

接口这边用了 `@Param`：

```java
int insert(@Param("userId") Long userId, @Param("roleId") Long roleId);
```

因为方法有**多个参数**，MyBatis 默认只能按 `arg0` / `param1` 这种位置名引用，XML 里的 `#{userId}` 会找不到。`@Param` 把名字显式绑上去。单参数方法（如 `selectById(Long id)`）不需要，因为只有一个参数时 MyBatis 能直接取到它。

### 9. 事务边界在 Service

```java
@Transactional
public User register(String username, String rawPassword, String nickname) {
```

`@Transactional` 加在 `UserService` 方法上，不在 Mapper 上。Mapper 是"一次数据库交互"，Service 方法才是"一件事"——注册这件事包含两条 INSERT，必须一起成功或一起失败。事务边界画在 Service，也让 Mapper 保持纯粹的 SQL 载体。

---

## 走一遍场景

**场景：注册一个用户名已存在的账号**

1. 请求进 `AuthController.register`，DTO 校验通过（长度、非空、格式）。
2. 进 `UserService.register`，事务开启。密码先 `passwordEncoder.encode` 成 BCrypt 哈希——**实体里存的是哈希，永远不是明文**。
3. 调 `userMapper.insert(user)`。MyBatis 找到 `UserMapper.xml` 的 `<insert id="insert">`，发出 `INSERT INTO t_user (...) VALUES (?, ...)`。
4. 数据库里已有同名用户，`uk_username (username)` 唯一索引挡住这条插入，抛异常。
5. 异常上抛，被 `catch (DuplicateKeyException e)` 接住：

```java
try {
    userMapper.insert(user);
} catch (DuplicateKeyException e) {
    log.debug("用户名已存在: {}", username);
    throw new BizException(ErrorCode.USERNAME_EXISTS);
}
```

6. `BizException` 冒泡出事务边界，事务回滚，全局异常处理器把它变成统一的错误响应。

**这里为什么不是"先查再插"**：`selectByUsername` 返回 null 之后、`insert` 之前，存在一个窗口——两个并发请求都能查到"没被占用"，然后都插进去。唯一索引是数据库层面对这个名字的**唯一裁决者**，只有它能保证并发下的唯一。所以流程是"直接插，失败靠捕获"，而不是"先问一遍数据库，然后再插"（问和插之间没人拦着）。这个捕获不是异常处理技巧，是正确性设计的一部分。

**场景：修改资料（只改昵称）**

`PATCH /api/users/{id}` → 归属校验（`@authz.isOwner(#id)`）通过 → `UserService.updateProfile(id, "新昵称", null, null)`：
- 先检查"三个字段至少有一个非 null"，都空就抛 `PARAM_INVALID`，不给它机会走到空 SET 语法错误；
- 组装一个只设了 `id` 和 `nickname` 的 `User` 当 patch 对象；
- 生成的是 `UPDATE t_user SET nickname = ? WHERE id = ? AND deleted = 0`，`avatar` / `email` 原样不动。

**场景：已逻辑删除的用户**

`DELETE` 在这套设计里不是真删，而是 `UPDATE t_user SET deleted = 1`。之后：
- `selectByUsername` 带 `deleted = 0`，查不到 → **删掉的用户登不进来**（这就是"过滤条件不能漏"的原因）；
- 但 `uk_username` 建在 `username` 单列上、**不带 `deleted`**，所以这个用户名仍被占用，别人不能注册同名。理由写在建表脚本的 `uk_username` 注释里（`db/V1__init_user.sql`）。

---

## 面试会追问

**Q：为什么用 MyBatis 不用 MyBatis-Plus？**
理由在 `docs/02-decisions/ADR-0012-persistence-mybatis.md` §理由，一句话版：M6 的深翻页亮点（Deferred Join）**本身就是手写 SQL**，Plus 的分页插件在这个场景帮不上忙，反而会让人绕过那个优化。**用 Plus 的代价不是性能差一点，是把最大的一个亮点关掉了。** 另见 m1-user-and-security.md §8。

**Q：为什么 SQL 写在 XML，不用注解（`@Select("...")`）？**
同上一条的延伸：注解里的 SQL 一长、一带 `<if>` 动态拼接就几乎没法读，而且改缩进要重新编译。M6 要能自由地改 SQL 并配 `EXPLAIN` 做前后对比，XML 改完重启即可。现在养成 XML 的习惯，M6 就不必临时换风格。

**Q：`map-underscore-to-camel-case` 不开会怎样？**
下划线字段映射不上 → **静默为 null，不报错**。要修就得每列手写 `<resultMap>`，而漏一行的后果是一样的静默。这不是风格选项，是消掉一类无堆栈的 bug。

**Q：忘了配 `useGeneratedKeys` 呢？**
`user.getId()` 是 null，注册流程第二步 `userRoleMapper.insert(user.getId(), ...)` 会写入 null。注意失败点离原因很远——用户表插成功了，坏的是关联表。

**Q：用户名唯一为什么不是先查再插？**
并发窗口：两个请求都能查到"没被占用"，然后都能插成功。唯一索引是数据库给的唯一裁决，可靠做法是"直接插 + 捕获 `DuplicateKeyException`"。`UserServiceTest` 里有对应的用例钉住这条：`thenThrow(new DuplicateKeyException("uk_username"))` → 断言转成 `USERNAME_EXISTS`。

**Q：逻辑删除的代价是什么？**
两个：① 每个查询都得记得带 `deleted = 0`（漏一处就是"已删用户还能登录"）；② 唯一索引要面对"已删除的用户名还占不占用"。本项目的取舍是**删除后用户名不释放**，理由写在 `V1__init_user.sql` 的 `uk_username` 注释里——把 `deleted` 加进唯一索引会让同一用户名可以存在多条"已删除"记录，反倒需要额外清理逻辑才能保证唯一，不值当。

**Q：为什么不建外键？**
外键约束与逻辑删除（行还在）配合别扭，且写入时的级联检查有开销。代价是**id 是否真实存在改由业务保证**——注册流程里的用户 id、角色 id 都出自同一事务，可以保证；绕过 Service 直接写库就会留下脏数据。

**Q：`<set>` 里的字段全是 null 会怎样？**
生成 `UPDATE t_user WHERE id = ?`，语法错误。所以 Service 层先拦：三个字段全空直接抛 `PARAM_INVALID`。让它在 Java 层失败，报错才说得清。

**Q：`updateProfile` 的返回值 `int` 有什么用？**
它是受影响行数，但 `UserService` 目前直接丢弃了。这里有个可以深挖的点：`WHERE ... AND deleted = 0` 意味着**对已删除用户改资料会更新 0 行且完全静默**。当前流程在归属校验阶段就已拿到用户，走不到这里；但如果将来有别的入口直接调它，`int` 用来断言"确实改了 1 行"会比丢弃它更稳。

**Q：`INSERT IGNORE` 和 `ON DUPLICATE KEY UPDATE` 怎么选？**
建表脚本里初始化三个角色用的也是 `INSERT IGNORE`，注释里写了原因：`ON DUPLICATE KEY UPDATE` 依赖 `VALUES()` 或行别名语法，而 `VALUES()` 从 MySQL 8.0.20 起已废弃，用它会在启动时刷 deprecation 警告。这里要的语义只是"没有就插入"，`INSERT IGNORE` 更贴合意图。

---

## 引用

| 文档 | 对应本文哪部分 |
|---|---|
| `docs/02-decisions/ADR-0012-persistence-mybatis.md` | 为什么 MyBatis、为什么 SQL 写 XML |
| `docs/03-design/m1-user-and-security.md` §2 | `t_user` 库表设计、逻辑删除的取舍 |
| `db/V1__init_user.sql` | 表结构、唯一索引、`uk_username` 不带 `deleted` 的理由 |
| `app/src/main/resources/application.yml`（`spring.datasource` / `mybatis` 段） | 连接参数与 MyBatis 配置，每条参数的坑 |
| `app/src/test/resources/application-test.yml` | 测试库指向、集成测试打印 SQL |
| `docs/06-runbook/local-setup.md` §3.1–3.2 | 建库建账号、脚本执行（开发库与测试库都要跑） |
| 代码 | `forum/forum-user/.../mapper/UserMapper.java` · `mapper/UserMapper.xml` · `mapper/RoleMapper.java` · `mapper/UserRoleMapper.java` · `mapper/RoleMapper.xml` · `mapper/UserRoleMapper.xml` · `entity/User.java` · `service/UserService.java` |
