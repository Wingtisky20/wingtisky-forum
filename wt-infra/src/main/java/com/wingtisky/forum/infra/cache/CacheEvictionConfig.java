package com.wingtisky.forum.infra.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 把"订阅失效广播"这件事接上 Spring 的生命周期。
 *
 * <p><b>为什么用 {@link RedisMessageListenerContainer} 而不是自己起一个线程订阅</b>：
 * 它管三件容易写错的事——**断线重连、订阅线程池、收到消息后交给谁**。
 * 尤其第一条：自己写的话，连接断了之后订阅就静默失效了——
 * 不报错、也不再有失效广播，只表现为"别的节点缓存一直是旧的"。
 * 这种失效方式极难发现，而它恰好是这套机制最怕的。
 *
 * <p>它是 `spring-boot-starter-data-redis` 自带的，**没有引入新依赖**。
 *
 * <p><b>启动期的一条已知取舍</b>：这个容器会在启动时尝试订阅。Redis 不可用时
 * 它不会让应用启动失败（容器的重连逻辑会一直重试并打日志），
 * 所以"M1 起就有的那条属性——不碰认证与限流的接口时，Redis 没起也能启动"仍然成立。
 * 这条属性由集成测试守着（见 {@code CacheEvictionRedisIntegrationTest}）。
 */
@Configuration
public class CacheEvictionConfig {

    @Bean
    public RedisMessageListenerContainer cacheEvictionListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheEvictionBroadcaster broadcaster) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                broadcaster, new ChannelTopic(CacheEvictionBroadcaster.CHANNEL));
        return container;
    }
}
