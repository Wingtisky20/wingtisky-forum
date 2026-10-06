# 链路讲解 · 限流：ZSet 滑动窗口与降级

> 亮点 5。设计决策记在 `docs/03-design/m1-user-and-security.md` §5 与 `ADR-0014`，
> M2 的两处扩展记在 `docs/03-design/m2-content-core.md` §5。
> 本文只讲**代码实际怎么走**：请求进来以后经过谁、每段代码做了什么、
> 出故障时走哪条岔路。要争论"为什么这么选"，去那几份文档，这里不复述。

---

## 一句话

把 Redis 的 ZSet 当成一个"带时间戳的窗口"：每次请求的时间戳存成 score，
判定和写入用**一次 Lua 脚本原子完成**；Redis 挂掉时按接口分类降级——
普通接口放行、登录注册退回单机内存。

---

## 数据怎么走

一次请求从进到出，限流只发生在中间那一小段：

```
浏览器 / curl
   │
   ▼
Tomcat ──► Spring Security 过滤器链（JwtAuthenticationFilter 验令牌，建立 SecurityContext）
   │
   ▼
DispatcherServlet ──► RateLimitInterceptor.preHandle()      ◄── 限流就在这一层
   │                     │
   │                     ├─ 从配置里找匹配的规则（路径 + 方法都命中；没匹配到 → 直接放行）
   │                     ├─ 拼出 Redis key（维度 + 取值 + 方法 + 接口路径）
   │                     ├─ redisLimiter.tryAcquire(key, limit, windowMs)
   │                     │      └─► Redis 执行 Lua：清窗口 → 计数 → 判断 → 记本次
   │                     ├─ 放行  → return true，继续走 Controller
   │                     └─ 拒绝  → throw BizException(TOO_MANY_REQUESTS)
   │
   ▼
Controller
   │
   ├─（登录接口）AuthService.login ──► checkUsernameRateLimit   ◄── 用户名维度的限流在这里
   │
   └─ 若抛了 BizException ──► GlobalExceptionHandler ──► HTTP 429 + 统一响应体
```

两个要点先记住：

- **拦截器在 Spring Security 之后**。这不是随便挑的层——按用户维度限流要拿
  `SecurityContextHolder` 里已经认证好的 userId，过滤器链之前是拿不到的（见下文逐段）。
- **拒绝走的是异常，不是直接写响应**。`BizException` 带着 `ErrorCode.TOO_MANY_REQUESTS`
  （`A0004`，HTTP 状态 `429`，见 `ErrorCode.java`），由全局异常处理器统一翻译成响应体。
  好处是响应格式和别的错误一致，前端不用为限流单独写一套解析。

---

## 逐段讲代码

### 1. key 怎么长——`RedisKey.rateLimit`

```java
public static String rateLimit(String dimension, String value, String method, String path) {
    return PREFIX + "rate:" + dimension + ":" + value + ":" + normalize(method + ":" + path);
}

private static String normalize(String suffix) {
    return suffix.replace('/', '_');
}
```

（`wt-infra/.../redis/RedisKey.java`。`PREFIX` 是 `"wt:"`。）

四段拼出来的样子，帖子列表按登录用户限就是：

```
wt:rate:user:42:GET:_api_posts
 │    │   │    │  │       │
 │    │   │    │  │       └─ path：接口路径（斜杠换成下划线）
 │    │   │    │  └─ method：请求方法
 │    │   │    └─ value：维度取值（IP、用户 ID 或用户名）
 │    │   └─ dimension：ip / user / username
 │    └─ 用途段
 └─ 全局前缀
```

四段**缺一不可**，这不是为了好看：少了 `path` 那段，不同接口会共用同一个计数器——
今天访问帖子、明天访问订单，各来一次就凑够 100 次被误判超限。
`method` 那段是 M2 加的，理由见下文「M2 的扩展」——同一条路径上的读与写用的是两套阈值，
不带方法它们就会共用一个计数器。两条都写在类注释里。

路径里的 `/` 替换成 `_`，是为了让 RedisInsight 这类工具按冒号折叠出的层级结构正确，
不会把 `/api/auth/login` 里的斜杠也当成分隔。

### 2. 核心：Lua 脚本——`SlidingWindowRateLimiter`

整个亮点的心脏就是这段脚本，**逐字照抄**自源文件：

```lua
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local member = ARGV[4]

-- 1. 清掉窗口外的记录（score 是时间戳）
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- 2. 数窗口内还剩多少
local count = redis.call('ZCARD', key)

-- 3. 已达上限则拒绝，且**不记录本次**
if count >= limit then
    return {0, count}
end

-- 4. 未超限：记录本次并续期
redis.call('ZADD', key, now, member)
redis.call('PEXPIRE', key, window)
return {1, count + 1}
```

四步一句一句看：

1. **`ZREMRANGEBYSCORE key 0 (now-window)`** —— 把 score 落在窗口之外的老记录删掉。
   score 存的是**毫秒时间戳**，所以"窗口外"就是"时间戳比 `now-window` 还早"。
2. **`ZCARD key`** —— 数一下窗口内还剩几条，就是这段时间实际放行过多少次。
3. **`count >= limit` 就返回 `{0, count}`** —— 注意拒绝时**不写本次记录**。
   如果被拒的请求也记进去，ZSet 会被攻击流量撑大，而且下一次判定要清更多垃圾。
4. **没超限才 `ZADD` + `PEXPIRE`** —— 记下本次（`member` 作成员，`now` 作 score），
   并把 key 的存活时间续成整个窗口。

返回一个二元组 `{是否放行, 当前计数}`。**为什么必须是一个脚本**：这四步如果拆成四次
网络往返，A 请求数完发现没超限、还没来得及 `ZADD`，B 请求也数完发现没超限——两个都放行，
限流就漏了。这和秒杀扣库存是同一类问题（读写之间被别人插队），解法也一样：
**判定和写入放进一次原子操作**。详见设计 §5.2。

### 3. Java 侧怎么调——`SlidingWindowRateLimiter.tryAcquire`

```java
public RateLimitResult tryAcquire(String key, int limit, long windowMs) {
    long now = System.currentTimeMillis();
    // member 唯一即可；nanoTime 在同一 JVM 内单调，配合纳秒后缀足够避免碰撞
    String member = now + "-" + System.nanoTime();

    @SuppressWarnings("unchecked")
    List<Long> result = (List<Long>) redis.execute(
            script, List.of(key), String.valueOf(now), String.valueOf(windowMs),
            String.valueOf(limit), member);

    if (result == null || result.size() < 2) {
        // 脚本返回值异常（理论上不会发生）。当成"放行"会让限流静默失效，
        // 当成"拒绝"会让全站不可用——两者都不该由这里决定。
        throw new IllegalStateException("限流脚本返回了非预期的结果: " + result);
    }
    return new RateLimitResult(result.get(0) == 1L, result.get(1).intValue(), limit);
}
```

三个值得停下来看的地方：

- **`member = now + "-" + System.nanoTime()`**。member 必须**唯一**，否则同一毫秒内的
  两个请求会互相覆盖（ZSet 成员相同 = 只留一条），ZSet 里的条数少于实际请求数，
  限流会**静默地放过多余的请求**——不报错，只是数字不对，最难查的那种 bug。
  用 `nanoTime()` 而不是随机数，还因为**脚本里不能用 `math.random`**：Redis 要求脚本
  可重放（主从复制时副本会重跑脚本），随机数会让主副本算出不同结果。所以随机性放在
  Java 侧生成 member，脚本保持确定性。这两条都写在类注释里。

- **脚本对象只 new 一次**（构造器里 `setScriptText` 后存成字段）。`DefaultRedisScript`
  走 `EVALSHA`，脚本在服务端缓存，不用每次把脚本体传过去。

- **这个类不返回"该放行还是该拒绝"，也不吞异常**：Redis 不可用时直接抛出去，
  由调用方决定怎么降级。类注释说得很直白——"这个类不知道该放行还是该拒绝，那是策略"。
  这就是 architecture.md `§4.3` 画的**机制 vs 策略**那条线：`wt-infra` 只管"怎么限"，
  "限多少、挂了怎么办"留在业务模块。

### 4. 谁来决定限多少、挂了怎么办——`RateLimitProperties` + `RateLimitInterceptor`

`RateLimitProperties` 绑定 `application.yml` 的 `wt.rate-limit.*`，每条规则这些字段：
路径模式、适用方法（可留空，空 = 不限方法）、维度（`ip` / `user`）、阈值、窗口、
降级策略（`open` / `local` / `closed`）。
规则**按顺序匹配，第一条命中的生效**，所以更具体的路径必须排在通配前面——
`/api/auth/login` 要写在 `/api/**` 之前，否则登录接口会先被那条宽松的默认规则吃掉。
加了方法维度之后，顺序还多一层讲究：`POST /api/posts`（发帖 10/分钟）
必须排在 `POST /api/**`（其余写接口 30/分钟）之前，否则后者先命中。
方法维度是 M2 加的，为什么值得单独讲一段，见下文「M2 的扩展」。

拦截器把"规则"和"执行"接起来，`preHandle` 是一条直线：

```java
RateLimitProperties.Rule rule = matchRule(request);
if (rule == null) {
    return true;                       // 没规则 → 不限流
}

// Key 里带上方法：同一条路径上的读与写用两套阈值，
// 不带方法它们就会共用一个计数器（详见 RedisKey.rateLimit 的说明）
String key = RedisKey.rateLimit(
        rule.getDimension().name().toLowerCase(),
        resolveDimensionValue(rule, request),
        request.getMethod(),
        request.getRequestURI());

boolean allowed;
try {
    allowed = redisLimiter.tryAcquire(key, rule.getLimit(), rule.getWindow().toMillis()).allowed();
} catch (RuntimeException e) {
    // ⚠️ 只包住 Redis 调用本身。若把下面的 BizException 也包进来，
    // "超限被拒"会被误判成"Redis 故障"而走降级分支——限流器会自己把自己放行。
    return handleFallback(rule, key, e);
}

if (!allowed) {
    log.warn("触发限流: path={}, dimension={}, key={}, limit={}/{}",
            request.getRequestURI(), rule.getDimension(), key,
            rule.getLimit(), rule.getWindow());
    throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
}
return true;
```

**那个 `try` 的范围是刻意收窄的**，注释里写了反面：如果把下面的 `if (!allowed) throw`
也圈进 try，那么"确实超限被拒"时抛出的 `BizException` 会被自己的 `catch` 接住，
当成"Redis 故障"再走一遍降级——降级里 fail-open 就是 `return true`，等于**超限的请求
被放行了**。限流器把自己短路掉，这种 bug 不看注释很难想到。

**维度取值**由 `resolveDimensionValue` 决定：`user` 维度先看 `SecurityContextHolder` 里
有没有认证过的 userId，拿得到就用用户 ID；拿不到（未登录）**退回 IP**——否则"先不登录
地打"就成了绕过限流的口子。`ip` 维度则直接取 `request.getRemoteAddr()`。

这里**刻意不读 `X-Forwarded-For`**：那个头客户端能随便伪造，在没有反向代理的情况下信任它，
攻击者每次换一个 XFF 值就能让 IP 限流形同虚设。等 M9 上了 Gateway，XFF 由网关写入、
才可信，但那时也必须**只取网关追加的那一段**（而不是整串），否则客户端仍能在前面塞假值。
这条提醒写在方法注释里，免得 M9 时忘了。

### 5. 降级：三种，不是两种——`handleFallback`

```java
private boolean handleFallback(RateLimitProperties.Rule rule, String key, RuntimeException cause) {
    // 记 ERROR 而不是 WARN：限流正在失效，这是需要被看到的信号
    log.error("限流降级：Redis 不可用，path={}, 策略={}", rule.getPath(), rule.getFallback(), cause);

    switch (rule.getFallback()) {
        case OPEN:
            return true;                       // 放行
        case LOCAL:
            if (!localLimiter.tryAcquire(key, rule.getLimit(), rule.getWindow().toMillis())) {
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
            }
            return true;
        case CLOSED:
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);   // 拒绝一切
        default:
            return true;
    }
}
```

走哪条路**来自配置**（`rule.getFallback()`），不是写死在代码里的判断。为什么是这三种、
为什么按接口分而不是一刀切——判据拆成了两个问题：

1. **限流在这里是不是"正确性防线"？——不是。** 超卖靠 Lua 原子扣减、重复下单靠幂等，
   限流失效不会让钱算错。
2. **两种失败的代价哪个大？** fail-closed 等于 Redis 抖一下全站 502，用"可能被刷"
   换"全站不可用"不划算。

但**登录/注册是个例外**：那个限流的唯一目的就是防撞库，放行等于在最需要它的时候把门打开，
全拦又谁都登不进来——所以退回单机内存（`local`），精度差但还有保护。完整的论证在
**`ADR-0014`**，这里只记结论与代码落点。

注意**打的是 `ERROR` 而不是 `WARN`**：限流正在失效，这是需要被人看到的信号。
日志里还带了 path 和策略，排查时一眼能看出是哪种降级。

### 6. 本地兜底——`LocalRateLimiter`

```java
public boolean tryAcquire(String key, int limit, long windowMs) {
    if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
        return false;
    }
    long now = System.currentTimeMillis();
    Window window = windows.compute(key, (k, existing) -> {
        if (existing == null || now - existing.startMillis >= windowMs) {
            return new Window(now);
        }
        return existing;
    });
    return window.count.incrementAndGet() <= limit;
}
```

它用 `ConcurrentHashMap` + 每个 key 一个 `AtomicInteger` 计数，**是固定窗口**——
存在设计 §5.1 说的边界问题（窗口交界处可能放过接近两倍）。作为临时兜底可以接受，
但不能把它当成 Redis 版的等价替代，类注释里明确写了。

一个容易忽略的细节：`MAX_KEYS = 10_000`。兜底只在 Redis 故障期间生效，正常情况下这个 Map
是空的；但万一 Redis 长时间不可用，不设上限的话它会被无限的 IP 撑爆内存——
**兜底机制自己把服务搞崩是最糟的失败方式**。所以满了以后**拒绝新 key 而不是无脑放行**，
已有的 key 继续正常计数。

### 7. 挂到哪儿——`WebConfig`

```java
@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new RateLimitInterceptor(
                    rateLimitProperties, slidingWindowRateLimiter, localRateLimiter))
            .addPathPatterns("/api/**");
}
```

拦的是 `/api/**` 而不是逐个列出接口：新增接口时**默认就被限流**，漏挂的表现是
"这个接口不限流"——不报错，只静默地敞着。所以用通配，宁可多拦也不要漏。
具体限多少由 `RateLimitProperties` 的规则决定，没匹配到规则的路径直接放行
（比如将来的健康检查接口）。

### 8. 拦截位置：为什么是 `HandlerInterceptor` 不是 Servlet 过滤器

`RateLimitInterceptor` 实现的是 `org.springframework.web.servlet.HandlerInterceptor`。
选择理由在类注释里：**拦截器在 Spring Security 过滤器链之后执行**，因此能拿到已经建立好的
认证上下文——按用户维度限流需要它。`resolveDimensionValue` 里那个
`SecurityContextHolder.getContext().getAuthentication()` 正是靠这个顺序才有值。
放进 Security 的过滤器链里也能做，但要自己处理过滤器顺序，收益为零。

M9 之后要前移到 Gateway（architecture.md §4.1：在网关挡掉无效流量比在每个服务里挡更省资源），
现在不做，因为 M1–M8 只有一个进程。

---

## 走一遍场景

拿 `api-smoke.http` 第 15 条——登录接口连打 11 次——从两个不同角度看。

**场景 A：阈值内，10 次正常放行。**

规则命中 `/api/auth/login`：`dimension=ip`、`limit=10`、`window=1m`、`fallback=local`。
假设客户端 IP 是 `127.0.0.1`，key 就是 `wt:rate:ip:127.0.0.1:POST:_api_auth_login`。

| 第几次 | Lua 走到哪 | Redis 里的状态 | 返回 | 对外的结果 |
|---|---|---|---|---|
| 第 1 次 | 清空 → `ZCARD=0` → 未超 → `ZADD` + `PEXPIRE` | ZSet 有 1 条 | `{1,1}` | 放行 → 走登录 → **401**（密码错） |
| 第 5 次 | `ZCARD=4` → `ZADD` | 5 条 | `{1,5}` | 放行 → **401** |
| 第 10 次 | `ZCARD=9` → `ZADD` | 10 条 | `{1,10}` | 放行 → **401** |

**关键就在这里**：前 10 次返回的是 **401（账号密码错）而不是 429**。这证明请求真的走到了
登录逻辑——一个"永远返回 429"的系统也能让限流测试"通过"，但那种系统等于把所有用户都拦了。
**阈值内必须真的放行**，这是本文档要对照的反面（api-smoke 第 15 条的注释专门提醒了这一点）。

**场景 B：第 11 次触发限流。**

- Lua 里 `ZCARD` 数出 10，`10 >= 10` 成立 → 返回 `{0, 10}`，**不写记录**。
- Java 侧 `allowed=false` → 抛 `BizException(TOO_MANY_REQUESTS)`。
- `GlobalExceptionHandler` 翻译成 **HTTP 429** + 统一响应体 `code=A0004`。

**场景 C：Redis 挂了。**

`redis.execute` 抛异常 → `catch` 接住 → `handleFallback`。因为这条规则是 `local`：

- 打一条 `ERROR 限流降级：Redis 不可用, path=/api/auth/login, 策略=LOCAL`。
- 转去 `localLimiter.tryAcquire`——单机内存继续数，**仍然是 10 次/分钟**。
- 若本地也超了 → 抛 `BizException` → 429；否则放行。

对照一下 `ADR-0014` 的实测：停掉 Redis 后，登录接口 `fallback=local` **仍限在 10 次**，
第 11 次 429；普通接口 `fallback=open` **正常放行 200**，没被误伤。两条路径都验证过，
不是纸面设计。

---

## M2 的扩展：方法维度与用户名维度

上面那条链路是 M1 建起来的。M2 的 Task 9 在它上面加了两处，原有的代码一行没删：
一处是把规则从"只按路径"扩成"路径 + 方法"，一处是补上 M1 有意留下的用户名维度缺口。

### 扩展一：规则可以区分 HTTP 方法

M1 的规则只按路径匹配，一条 `/api/posts` 会同时管住公开的列表查询和发帖——
按发帖的严格阈值设，读就被误伤；按读的宽松阈值设，发帖又等于没限。
M2 给 `Rule` 加了一个 `methods` 字段：

```java
/**
 * 这条规则适用的 HTTP 方法，如 {@code [POST, PUT, PATCH, DELETE]}。
 *
 * <p><b>空（或不填）表示不限方法</b>，但那种用法要小心：同一条路径上的
 * 读与写通常需要**两套阈值**（读宽松、写严格）。不区分方法的话，
 * 一条 {@code /api/posts} 的规则会同时管住公开的列表查询和发帖——
 * 按发帖的严格阈值设，读就被误伤；按读的宽松阈值设，发帖又等于没限。
 */
private List<HttpMethod> methods;
```

（`forum/.../ratelimit/RateLimitProperties.java`。注释末尾还写了这条设计的**代价**：
一条路径可能要写两条规则，一条给读、一条给写——换来的是"阈值与操作重量挂钩"这件事在配置里看得见。）

判断方法命中的 `methodMatches` 写着两件容易写错的事：

```java
private static boolean methodMatches(RateLimitProperties.Rule rule, String actualMethod) {
    List<HttpMethod> methods = rule.getMethods();
    if (methods == null || methods.isEmpty()) {
        return true;
    }
    return methods.stream().anyMatch(m -> m.name().equalsIgnoreCase(actualMethod));
}
```

第一，**没配方法就是不限方法**（`methods` 空则恒为 `true`）——老配置不用改就能继续用。
第二，比较用的是 `m.name().equalsIgnoreCase(...)` 而不是 `HttpMethod.matches(...)`：
前者不依赖 Spring 新增的 API，也不会因为将来多出某个方法而行为变化。

**真正容易写错的地方在它被调用的位置**。`matchRule` 把它**并进了"找规则"这一层**：

```java
private RateLimitProperties.Rule matchRule(HttpServletRequest request) {
    for (RateLimitProperties.Rule rule : properties.getRules()) {
        if (rule.getPath() != null
                && pathMatcher.match(rule.getPath(), request.getRequestURI())
                && methodMatches(rule, request.getMethod())) {
            return rule;
        }
    }
    return null;
}
```

如果把它拆出去，写成"先按路径找到第一条，再看方法对不对，不对就放行"，那么
`GET /api/posts` 会先命中 `POST /api/posts` 那条（发帖，10 次/分），
因为方法不符**径直放行**，后面那条读接口的规则（100 次/分）根本没机会被看到——
**表现是这条接口完全不受限流**，而配置看起来完全正确。这段反面就写在 `matchRule`
的方法注释里，第一版正是这么写的，被测试逮住。

配置里因此按操作重量分成三档（`app/src/main/resources/application.yml`）：

```yaml
      # 发帖：最重的写操作（要写正文、标签、计数），单独一档更严
      - path: /api/posts
        methods: [POST]
        dimension: user
        limit: 10
        window: 1m
        fallback: open

      # 其余写接口：评论、点赞、收藏、后台治理都落在这里
      - path: /api/**
        methods: [POST, PUT, PATCH, DELETE]
        dimension: user
        limit: 30
        window: 1m
        fallback: open

      # 读接口：按登录用户限。不用 IP 是因为同一个办公室共用一个出口 IP，
      # 按 IP 限会把无辜的人一起限掉。未登录时自动退回 IP。
      - path: /api/**
        methods: [GET]
        dimension: user
        limit: 100
        window: 1m
        fallback: open
```

**方法也必须进 Redis key**——否则同一条路径上的读与写会共用一个计数器，
读几十次就把写的那 10 次额度吃掉了，表现是"我明明没发几次帖，却被限流了"。
key 的构造在 §1 已经改成四段，理由写在 `RedisKey.rateLimit` 的注释里。

### 扩展二：用户名维度的限流（M1 留的缺口）

M1 设计 §5.4 明确留了这个缺口并写下了建议做法，M2 按它补上（设计稿 §5）。
它**不在限流拦截器里**，在 `AuthService.login` 里，而且放在**最前面**：

```java
public LoginResult login(String username, String rawPassword) {
    checkUsernameRateLimit(username);

    User user = userService.authenticate(username, rawPassword);
    List<String> roles = userService.getRoleCodes(user.getId());
    return issueTokens(user, roles);
}
```

放最前面的理由很实在：再往下走一步就是 BCrypt 比对（每次约 100ms），
放在后面的话撞库请求已经打到密码校验上了，限流来得太晚。

```java
private void checkUsernameRateLimit(String username) {
    if (username == null || username.isBlank()) {
        return;
    }

    RateLimitProperties.LoginUsernameLimit rule = rateLimitProperties.getLoginUsername();
    String key = RedisKey.rateLimit("username", username, "POST", "/api/auth/login");

    boolean allowed;
    try {
        allowed = redisLimiter.tryAcquire(key, rule.getLimit(), rule.getWindow().toMillis())
                .allowed();
    } catch (RuntimeException e) {
        // 记 ERROR 而不是 WARN：限流正在失效，这是需要被看到的信号
        log.error("用户名维度限流降级：Redis 不可用，退回本地内存。key={}", key, e);
        allowed = localLimiter.tryAcquire(key, rule.getLimit(), rule.getWindow().toMillis());
    }

    if (!allowed) {
        log.warn("触发用户名维度限流: username={}, limit={}/{}",
                username, rule.getLimit(), rule.getWindow());
        throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
    }
}
```

几处和拦截器版本不一样的地方：

- **为什么在业务方法里**：拦截器跑在 Controller 之前，**拿不到请求体里的用户名**；
  要在那里拿就得把请求包一层（`ContentCachingRequestWrapper`），而请求体是一次性的，
  读完后续 `@RequestBody` 就拿不到了。放到这里，用户名已经是方法参数。
  代价是限流逻辑分了两处——这段注释留在方法上，就是为了免得下一个人把它挪回拦截器里，"一挪就坏"。
- **降级策略写死在代码里，配置里没有对应字段**：`LoginUsernameLimit` 只有 `limit` 和 `window`。
  这一维度 Redis 挂了**一律退回本地内存**——"放行"等于把门打开、"拒绝"等于谁都登不进来，
  只有一条路可走，就不必在配置里留一个永远不会改成别的值的选项。
- **用户名为空直接跳过**：避免拿空串当桶，把所有没带用户名的请求挤进同一个计数器。
- 它和 IP 维度**不是重复**：按 IP 挡"同一台机器试很多账号"，按用户名挡"很多台机器试同一个账号"，
  这是撞库的两种形态，只限一个都会漏。阈值 `5 / 分钟`（`wt.rate-limit.login-username`）
  也比 IP 维度更严——正常用户不会一分钟内连试 5 次密码。

---

## 面试会追问

**Q：固定窗口到底慢在哪，你举个例子？**
限"每分钟 100 次"。用户在 `00:59` 打满 100 次，`01:00` 又打满 100 次——跨边界的这两秒里
实际过了 200 次，而计数器认为一切正常。ZSet 每次只数"最近 60 秒内"的成员，
`00:59` 那 100 条还在窗口里，`01:00` 的新请求一进来就会被判超限，没有边界。
（设计 §5.1 记了这个对照。）

**Q：为什么不能用 `INCR` 加一段判断逻辑，非要 Lua？**
判断逻辑写在 Java 里就是"先读再写"，读和写之间有网络往返，A、B 两个请求会双双通过。
这与秒杀扣库存是同一个问题，所以解法也相同——放进 Redis 服务端一次算完。

**Q：member 直接用时间戳不行吗？**
不行。同一毫秒内的两个请求 member 相同，`ZADD` 会覆盖掉一条，ZSet 里的条数少于实际请求数，
限流**静默放过多余请求**。所以要拼一个唯一后缀（这里用 `nanoTime()`）。

**Q：为什么 member 不放 Redis 里随机生成？**
Redis 要求脚本可重放（主从复制时副本重跑脚本），`math.random` 会让主副本结果不一致。
随机性放 Java 侧，脚本保持确定性。

**Q：`PEXPIRE` 每次续期，key 会不会永远不过期？**
只要持续有请求，key 就一直活着——这是**故意的**。窗口靠 `ZREMRANGEBYSCORE` 滑动，
不靠 key 过期。反过来如果 key 一到期计数归零，那就退化成固定窗口了。
集成测试里专门有一条 `windowActuallySlidesWhileTheKeyStaysAlive` 盯着这件事：
请求**分散**发、让 key 始终存活，才能测出"窗口在滑"而不是"key 过期了计数归零"。

**Q：Redis 挂了为什么不全拦（fail-closed）？**
因为限流不是正确性防线——超卖靠 Lua 扣减、重复下单靠幂等，限流失效不会让钱算错；
而 fail-closed 等于 Redis 单点抖一下全站 502。用"可能被刷"换"全站不可用"不划算。
详见 `ADR-0014 §理由`。

**Q：那登录接口为什么又不 fail-open？**
因为登录接口的限流，**目的本身就是安全**——防撞库。普通接口限流是保护后端资源，
失效了只是压力大点；登录限流失效就是攻击者可以无限猜密码。目的不同，降级方式就不同，
所以退回单机内存。**这也是为什么是"三种降级按接口分类"而不是"全局选一个"。**

**Q：本地兜底是完美的吗？**
不是，两处明确短板，都写在 `LocalRateLimiter` 注释里：
- 它是**固定窗口**，有边界问题（窗口交界可能放过接近两倍）；
- M9 拆多实例后**每个实例各限一份**，N 个实例放行 N 倍。届时要么给 Redis 上哨兵、
  要么登录改成 fail-closed。现在不做（YAGNI），但记着这个时点。

**Q：为什么用拦截器不用过滤器？**
拦截器在 Security 过滤器链之后，能拿到 `SecurityContextHolder` 里认证好的 userId。
按用户维度限流需要它。这条是硬理由，不是风格偏好。

**Q：为什么不直接用 Sentinel，或者 Redisson 的 `RRateLimiter`？**
Sentinel 会抢戏——亮点是自己实现的 ZSet 滑动窗口，换成 Sentinel 就变成"调了个 API"，
面试价值归零（architecture.md §4.2 记了这条）。Redisson 的 `RRateLimiter` 内部也是令牌桶/
滑动窗口，用它会绕开这里要讲的实现细节。

**Q：为什么 IP 限流不用 `X-Forwarded-For`？**
那个头客户端可伪造。没有反向代理时信任它，攻击者每次换一个 XFF 值就能绕过 IP 限流。
M9 上了 Gateway 才能信，且只取网关追加的那一段。

**Q：登录的"按用户名限流"为什么放在 `AuthService` 里，不放进拦截器？**
（M1 设计 §5.4 说它是留待后补的缺口，M2 已按该节的建议补上，见上文「M2 的扩展」。）
拦截器跑在 Controller 之前，**拿不到请求体里的用户名**。要在那里拿，就必须先读请求体，
而请求体是一次性的——读完后续 `@RequestBody` 就拿不到数据了，
得加一层 `ContentCachingRequestWrapper` 把请求包起来。
`AuthService.login` 里用户名**已经是方法参数**，直接用即可，不需要任何请求包装。
代价是限流逻辑从"统一拦截器"分出一点到业务层，方法注释里写明了这是有意取舍。

---

## 引用

| 文件 | 提供了什么 |
|---|---|
| `wt-infra/src/main/java/com/wingtisky/forum/infra/redis/SlidingWindowRateLimiter.java` | Lua 脚本本体、member 唯一性与 `math.random` 的两条理由 |
| `wt-infra/src/main/java/com/wingtisky/forum/infra/redis/RedisKey.java` | `rateLimit` 四段 key 的构造，方法为什么必须进 key、缺 path 会误判的理由 |
| `forum/forum-user/src/main/java/com/wingtisky/forum/forum/user/ratelimit/RateLimitInterceptor.java` | 拦截入口、`matchRule` / `methodMatches`、try 范围收窄的反面、降级分支、维度取值与 XFF 取舍 |
| `forum/forum-user/src/main/java/com/wingtisky/forum/forum/user/ratelimit/RateLimitProperties.java` | 规则模型、`Rule.methods`、`LoginUsernameLimit`、规则按序匹配、`Dimension` / `Fallback` 枚举 |
| `forum/forum-user/src/main/java/com/wingtisky/forum/forum/user/ratelimit/LocalRateLimiter.java` | 本地兜底实现、`MAX_KEYS` 上限、固定窗口与多实例短板 |
| `forum/forum-user/src/main/java/com/wingtisky/forum/forum/user/service/AuthService.java` | `login` 里调用 `checkUsernameRateLimit`、用户名维度为什么放在这里的注释 |
| `forum/forum-user/src/main/java/com/wingtisky/forum/forum/user/config/WebConfig.java` | 拦截器挂到 `/api/**` 的装配与"通配优于逐个列出"的理由 |
| `app/src/main/resources/application.yml` | `wt.rate-limit` 段的真实规则与注释（登录/注册 `local`、按方法分读 100 / 写 30 / 发帖 10 三档、`login-username` 段） |
| `docs/03-design/m1-user-and-security.md` §5 | 亮点 5 的设计决策：为什么 ZSet、为什么 Lua、降级三种、§5.4 用户名维度的缺口 |
| `docs/03-design/m2-content-core.md` §5 | M2 补用户名维度的做法与两处理由（阈值为什么是 5、为什么和 IP 维度不重复） |
| `docs/02-decisions/ADR-0014-rate-limit-degradation.md` | 降级决策的完整论证与**停 Redis 的实测结果** |
| `docs/06-runbook/api-smoke.http` | 第 15 条：阈值内必须 401 而非 429（反面对照的实测入口） |
| `app/src/test/java/com/wingtisky/forum/SlidingWindowRateLimiterIntegrationTest.java` | 边界测试：连真实 Redis、在窗口后段发请求、验证"窗口在滑"而非"key 过期" |
| `forum/forum-user/src/test/java/com/wingtisky/forum/forum/user/ratelimit/RateLimitInterceptorTest.java` | 方法维度测试：同路径按方法命中不同规则、key 里带方法、没配方法仍不限方法 |
| `forum/forum-user/src/test/java/com/wingtisky/forum/forum/user/service/AuthServiceTest.java` | 用户名维度测试：超限时不去验密码、按用户名分桶、Redis 挂了仍不放行、空用户名跳过 |
| `wt-common/src/main/java/com/wingtisky/forum/common/result/ErrorCode.java` | `TOO_MANY_REQUESTS` = `A0004` / HTTP 429 |
