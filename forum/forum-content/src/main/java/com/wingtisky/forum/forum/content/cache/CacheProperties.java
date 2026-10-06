package com.wingtisky.forum.forum.content.cache;

import com.wingtisky.forum.infra.cache.CachePolicy;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 缓存策略配置，绑定 {@code wt.cache.*}。**这几个数字是初始值，M10 压测时才校准。**
 *
 * <p><b>为什么进配置而不是写死在代码里</b>：校准是"试几组值比一比"的活。
 * 写死的话每改一次都要重新编译、重新部署，而那正是最需要反复试的时候。
 * （与 {@code wt.rate-limit} 那段的理由相同。）
 *
 * <p><b>为什么它放在业务模块，而不是 {@code wt-infra}</b>：这里的键名
 * （{@code post-detail}）本身就是业务概念——它回答的是"**帖子详情**缓存多久"，
 * 而不是"缓存怎么工作"。架构文档 §4.3 那条边界线写得很明确：
 * **缓存存多久属业务模块**，基础设施只管机制（连接、序列化、Key 规范、锁）。
 * 放反了的话，M9 拆服务时这条边界就说不清了。
 *
 * <p>注意 {@code toPolicy()} 的存在：配置这一侧是"人填的、可变的几个数"，
 * {@code wt-infra} 那一侧要的是"一整套存活策略"。转换只在这一处发生，
 * 别处不必知道它们其实是同一批数字。
 *
 * <h3>⚠️ 两个防止"填错了却没反应"的开关</h3>
 *
 * <p><b>{@code ignoreUnknownFields = false}：多写的键会直接让启动失败。</b>
 * 默认值是 {@code true}——**Spring 会安静地忽略掉它不认识的键**。
 * 这条默认行为踩过一次真的：yml 里写的是 {@code l2-ttl-jitter}，
 * 而字段叫 {@code l2Jitter}，两者对不上（中间那段 {@code ttl} 是多余的），
 * 于是那个值根本没绑上，字段是 {@code null}。**它不会在启动时报错**，
 * 要等到有人读详情、构造 {@code CachePolicy} 时才炸成 500。
 * 关掉它之后，这种拼写不一致会在启动那一刻就被拦下。
 *
 * <p><b>{@code @Validated} + {@code @NotNull}：少填的键同样让启动失败</b>，
 * 而不是留一个 {@code null} 到运行时。
 */
@Validated
@ConfigurationProperties(prefix = "wt.cache", ignoreUnknownFields = false)
public record CacheProperties(@NotNull PostDetail postDetail, @NotNull ViewCount viewCount) {

    /**
     * 帖子详情那几档时长。
     *
     * @param enabled   <b>总开关。</b>关掉 = Task 10 闸门实验里的「纯 MySQL」那一组。
     *                 它必须真的能关掉缓存——两组的差别只能有这一个变量，
     *                  否则比出来的差值里混着别的东西，说明不了缓存的作用
     * @param l1Ttl      进程内那份的存活时间。**必须短**：失效广播不可靠
     *                   （订阅者掉线期间的消息直接丢），L1 自己的过期时间是兜底
     * @param l2Ttl      Redis 里那份的基准存活时间
     * @param l2TtlJitter 写 L2 时叠加的最大随机量（防雪崩）。
     *                   <b>字段名里那段 {@code ttl} 是必须的</b>——它对应 yml 里的
     *                   {@code l2-ttl-jitter}（设计稿 §8 定的名字）。叫 {@code l2Jitter}
     *                   的话两边隐式对不上，而**这种对不上默认是不报错的**（见类注释）
     * @param nullTtl    "这篇帖子不存在"那条墓碑的存活时间。**必须短**——
     *                   一个新建的 id 会被还留着的墓碑当成不存在
     * @param lockWait   抢不到回源锁时最多等多久，超时则降级回源
     */
    public record PostDetail(boolean enabled,
                             @NotNull Duration l1Ttl,
                             @NotNull Duration l2Ttl,
                             @NotNull Duration l2TtlJitter,
                             @NotNull Duration nullTtl,
                             @NotNull Duration lockWait) {

        public CachePolicy toPolicy() {
            return new CachePolicy(l1Ttl, l2Ttl, l2TtlJitter, nullTtl, lockWait);
        }
    }

    /**
     * 浏览数计数器的配置。
     *
     * <p>它同样被 {@code PostViewCounter} 的 {@code @Scheduled} 直接读（按字符串读，
     * 因为注解里只能用字面量）。两处读的是**同一个键**，改一处两边都变——
     * 所以这里不复制它的值。
     *
     * @param flushInterval 把计数器的绝对值回写进数据库的间隔
     */
    public record ViewCount(@NotNull Duration flushInterval) {
    }
}
