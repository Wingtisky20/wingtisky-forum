package com.wingtisky.forum.forum.user.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;

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

    /**
     * 登录接口的**用户名维度**限流（{@code wt.rate-limit.login-username}）。
     *
     * <p><b>它为什么不放在 {@code rules} 里</b>：那些规则是按"路径 + 方法"匹配的，
     * 在拦截器里执行；而拦截器**拿不到请求体里的用户名**（请求体是一次性的，
     * 读掉之后 {@code @RequestBody} 就拿不到了）。
     * 所以这一维度由 {@code AuthService.login} 自己读——那里用户名已经是参数。
     *
     * <p>阈值比 IP 维度更严：它挡的是"很多台机器试同一个账号"，
     * 而正常用户一分钟内不会连试 5 次密码。
     */
    private LoginUsernameLimit loginUsername = new LoginUsernameLimit();

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

    public LoginUsernameLimit getLoginUsername() {
        return loginUsername;
    }

    public void setLoginUsername(LoginUsernameLimit loginUsername) {
        this.loginUsername = loginUsername;
    }

    /**
     * 用户名维度的阈值。
     *
     * <p>**没有降级策略字段**：这一维度 Redis 挂掉时一律退回本地内存。
     * 三种降级策略里，"放行"等于把门打开、"拒绝"等于谁都登不进来，
     * 都不合适——而它的唯一目的就是防撞库（见 {@code AuthService} 里的说明）。
     * 只留一条路，就不必在配置里写一个永远不会改成别的值的选项。
     */
    public static class LoginUsernameLimit {
        private int limit = 5;
        private Duration window = Duration.ofMinutes(1);

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

        /**
         * 这条规则适用的 HTTP 方法，如 {@code [POST, PUT, PATCH, DELETE]}。
         *
         * <p><b>空（或不填）表示不限方法</b>，但那种用法要小心：同一条路径上的
         * 读与写通常需要**两套阈值**（读宽松、写严格）。不区分方法的话，
         * 一条 {@code /api/posts} 的规则会同时管住公开的列表查询和发帖——
         * 按发帖的严格阈值设，读就被误伤；按读的宽松阈值设，发帖又等于没限。
         *
         * <p>诚实的代价：这意味着**一条路径可能要写两条规则**（一条给读、一条给写）。
         * 换来的是"阈值与操作重量挂钩"这件事在配置里看得见。
         */
        private List<HttpMethod> methods;

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

        public List<HttpMethod> getMethods() {
            return methods;
        }

        public void setMethods(List<HttpMethod> methods) {
            this.methods = methods;
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
