package com.wingtisky.forum.infra.redis;

/**
 * Redis Key 的命名规范。
 *
 * <p><b>这个类属于"机制"不是"策略"</b>（architecture.md §4.3）——它只回答
 * "一个 Key 该长什么样"，不回答"这个 Key 该活多久"。过期时间、哪些事件要幂等、
 * 索引怎么映射，都是业务语义，留在各自的业务模块。把 TTL 也塞进来的话，
 * 这里会慢慢变成一个"什么都懂"的上帝类。
 *
 * <p><b>命名格式</b>：{@code wt:<业务域>:<用途>:<标识>}
 *
 * <table>
 *   <tr><th>段</th><th>作用</th></tr>
 *   <tr><td>{@code wt}</td><td>全局前缀。M9 多服务会共用同一个 Redis，
 *       前缀是区分"哪个项目的 Key"的唯一手段；本地开发时也避免和别的程序撞</td></tr>
 *   <tr><td>业务域</td><td>{@code auth} / {@code rate} / {@code cache} …，按用途分</td></tr>
 *   <tr><td>标识</td><td>具体对象，如用户 ID、IP、接口路径</td></tr>
 * </table>
 *
 * <p><b>为什么用冒号分隔而不是下划线或驼峰</b>：Redis 生态的通用约定，
 * 且多数可视化工具（RedisInsight 等）会按冒号自动折叠成树，几百个 Key 时
 * 找起来差别很大。
 */
public final class RedisKey {

    /** 全局前缀。换项目时改这一处，不要在各方法里散落。 */
    private static final String PREFIX = "wt:";

    private RedisKey() {
    }

    /**
     * 刷新令牌。值存 jti，注销时删除此 Key 即完成撤销。
     *
     * <p>按用户 ID 而不是按令牌本身：这样"注销"是 {@code DEL} 一个确定的 Key，
     * 不需要先查出令牌内容。代价是同一用户的多端登录会互相顶掉——
     * 本项目接受这个代价（设计 §3.2 记的取舍）。
     */
    public static String refreshToken(Long userId) {
        return PREFIX + "auth:refresh:" + userId;
    }

    /**
     * 限流计数。
     *
     * <p>四段都要带上，缺一不可：
     * <ul>
     *   <li>{@code dimension} —— 限流维度（ip / user / username），决定"限制的是谁"</li>
     *   <li>{@code value} —— 维度取值（IP 地址、用户 ID、用户名）</li>
     *   <li>{@code method} —— 请求方法</li>
     *   <li>{@code path} —— 接口路径。**少了这段，不同接口会共用同一个计数器**，
     *       访问十个不同接口各一次就会被误判为超限</li>
     * </ul>
     *
     * <p><b>方法为什么也要进 Key</b>：同一条路径上的读与写用的是两套阈值
     * （`GET /api/posts` 每分钟 100 次、`POST /api/posts` 每分钟 30 次）。
     * 不带方法的话它们会**共用一个计数器**——发几次帖就把读的额度吃掉了，
     * 而表现是"我明明没读几篇，却被限流了"。
     *
     * <p>路径里的 {@code /} 替换成 {@code _}：冒号是层级分隔符，路径里的斜杠虽不冲突，
     * 但统一替换后 Key 在可视化工具里的层级结构才正确。
     */
    public static String rateLimit(String dimension, String value, String method, String path) {
        return PREFIX + "rate:" + dimension + ":" + value + ":" + normalize(method + ":" + path);
    }

    /**
     * 帖子详情的缓存键（M3 的亮点 1）。
     *
     * <p><b>为什么帖子的键写在这个类里，而不是写在 forum-content 自己的常量里</b>：
     * 这个类的职责就是"**Key 规范**"（见本类开头）——把全部 Key 收在一处，
     * 才能一眼看全"这套系统在 Redis 里到底用了哪些键"，也才能保证命名一致。
     * 散到各业务模块的话，同一个 Redis 上会慢慢长出几种互不相同的命名风格，
     * 而 M9 三个服务共用同一个 Redis 时，这件事会变成真的麻烦。
     *
     * <p><b>它只回答"键长什么样"，不回答"存多久"</b>——后者是
     * {@code wt.cache.*} 那组配置，属业务语义，留在 forum-content。
     */
    public static String cachePostDetail(Long postId) {
        return PREFIX + "cache:post:detail:" + postId;
    }

    /**
     * 回源锁的键：**由缓存键推导出来**，而不是另起一套命名。
     *
     * <p>推导而不是手写，是为了让"缓存键 ↔ 锁键"永远一一对应。
     * 手写两套的话，加缓存时忘了加锁、或者两处拼得不一样，都不会报错——
     * 只会表现为"锁没起作用"，而那正是最难发现的一类失效（表面一切正常，只是防击穿失效了）。
     *
     * <p>例：{@code wt:cache:post:detail:7} → {@code wt:lock:cache:post:detail:7}
     */
    public static String cacheLock(String cacheKey) {
        String bare = cacheKey.startsWith(PREFIX) ? cacheKey.substring(PREFIX.length()) : cacheKey;
        return PREFIX + "lock:" + bare;
    }

    private static String normalize(String suffix) {
        return suffix.replace('/', '_');
    }
}
