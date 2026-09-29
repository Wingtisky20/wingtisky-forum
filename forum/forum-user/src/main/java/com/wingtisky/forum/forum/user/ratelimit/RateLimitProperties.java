package com.wingtisky.forum.forum.user.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 限流策略配置，绑定 {@code wt.rate-limit.*}。
 *
 * <p><b>为什么阈值要可配置而不是硬编码</b>：设计 §5.4 写的那些数字（登录 10 次/分钟等）
 * 都是**初始值**，要在 M10 压测时校准。硬编码的话每次调整都要改代码、重新编译、
 * 重新部署；而且不同环境（本地/压测）本来就可能需要不同的值。
 *
 * <p><b>降级策略同样可配置</b>：设计 §5.3 的结论是"按接口分类"——
 * 普通接口 fail-open，登录接口降级到本地内存。如果把它写死在限流逻辑里，
 * 下次想调整就必须改代码，而"哪种接口该 fail-open"本身是个会变的判断。
 */
@ConfigurationProperties(prefix = "wt.rate-limit")
public class RateLimitProperties {

    /** 总开关。压测时可能要关掉它——否则限流本身会成为压测的瓶颈。 */
    private boolean enabled = true;

    /**
     * 规则列表，**按顺序匹配，第一条命中的生效**。
     *
     * <p>因此更具体的路径要写在前面：`/api/auth/login` 必须排在 `/api/**` 之前，
     * 否则后面的默认规则会先命中，登录接口就拿不到它自己的严格阈值了。
     */
    private List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    /** 限流维度：按什么区分"谁在请求"。 */
    public enum Dimension {
        /** 按来源 IP。用于**未登录**也能调的接口（登录、注册）。 */
        IP,
        /** 按登录用户。用于已认证的接口——同一个办公室的人共用一个出口 IP，
         *  按 IP 限会把无辜的人一起限掉。 */
        USER
    }

    /** Redis 不可用时的降级策略。 */
    public enum Fallback {
        /** 放行。限流是防滥用而非正确性防线，全站不可用的代价更大。 */
        OPEN,
        /** 退回单机内存计数。精度下降（多实例时每个实例各限一份），但仍有保护。 */
        LOCAL,
        /** 拒绝所有请求。**慎用**——Redis 抖一下就是全站不可用。 */
        CLOSED
    }

    /** 一条限流规则。 */
    public static class Rule {

        /** Ant 风格路径模式，如 {@code /api/auth/login} 或 {@code /api/**}。 */
        private String path;

        private Dimension dimension = Dimension.IP;

        /** 窗口内允许的最大请求数。 */
        private int limit = 100;

        private Duration window = Duration.ofMinutes(1);

        private Fallback fallback = Fallback.OPEN;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Dimension getDimension() {
            return dimension;
        }

        public void setDimension(Dimension dimension) {
            this.dimension = dimension;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public Fallback getFallback() {
            return fallback;
        }

        public void setFallback(Fallback fallback) {
            this.fallback = fallback;
        }
    }
}
