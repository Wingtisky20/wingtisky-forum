package com.wingtisky.forum.forum.content.config;

import com.wingtisky.forum.forum.content.cache.CacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link CacheProperties} 注册成一个 Bean。
 *
 * <p>本项目不用 {@code @ConfigurationPropertiesScan} 全包扫一遍，而是**一处一处显式声明**
 * ——与本模块无关的配置类不会被顺带实例化，从哪里来的、在哪儿生效一目了然。
 * 用户域那两个（{@code JwtProperties} / {@code RateLimitProperties}）也是这么做的。
 *
 * <p>它自己不提供任何 Bean：唯一的职责就是"让 {@code wt.cache} 这段配置有人管"。
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class ContentCacheConfig {
}
