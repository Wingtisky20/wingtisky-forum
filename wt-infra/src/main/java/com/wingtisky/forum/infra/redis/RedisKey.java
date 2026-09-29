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
     * <p>三段都要带上，缺一不可：
     * <ul>
     *   <li>{@code dimension} —— 限流维度（ip / user），决定"限制的是谁"</li>
     *   <li>{@code value} —— 维度取值（IP 地址或用户 ID）</li>
     *   <li>{@code path} —— 接口路径。**少了这段，不同接口会共用同一个计数器**，
     *       访问十个不同接口各一次就会被误判为超限</li>
     * </ul>
     *
     * <p>路径里的 {@code /} 替换成 {@code _}：冒号是层级分隔符，路径里的斜杠虽不冲突，
     * 但统一替换后 Key 在可视化工具里的层级结构才正确。
     */
    public static String rateLimit(String dimension, String value, String path) {
        return PREFIX + "rate:" + dimension + ":" + value + ":" + normalize(path);
    }

    private static String normalize(String path) {
        return path.replace('/', '_');
    }
}
