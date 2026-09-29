package com.wingtisky.forum.forum.user.config;

import com.wingtisky.forum.forum.user.ratelimit.LocalRateLimiter;
import com.wingtisky.forum.forum.user.ratelimit.RateLimitInterceptor;
import com.wingtisky.forum.forum.user.ratelimit.RateLimitProperties;
import com.wingtisky.forum.infra.redis.SlidingWindowRateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配：把限流拦截器挂到 {@code /api/**} 上。
 *
 * <p>用 {@code /**} 而不是逐个列出接口：新增接口时**默认就被限流**，
 * 漏挂的表现是"这个接口不限流"——不会报错，只会静默地敞着。
 * 至于具体限多少，由 {@link RateLimitProperties} 里的规则决定；
 * 没有匹配到规则的路径直接放行（比如将来的健康检查接口）。
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitProperties rateLimitProperties;
    private final SlidingWindowRateLimiter slidingWindowRateLimiter;
    private final LocalRateLimiter localRateLimiter;

    public WebConfig(RateLimitProperties rateLimitProperties,
                     SlidingWindowRateLimiter slidingWindowRateLimiter,
                     LocalRateLimiter localRateLimiter) {
        this.rateLimitProperties = rateLimitProperties;
        this.slidingWindowRateLimiter = slidingWindowRateLimiter;
        this.localRateLimiter = localRateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(
                        rateLimitProperties, slidingWindowRateLimiter, localRateLimiter))
                .addPathPatterns("/api/**");
    }
}
