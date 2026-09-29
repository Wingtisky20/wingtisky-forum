package com.wingtisky.forum.forum.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器。
 *
 * <p><b>为什么是 BCrypt</b>：
 * <ul>
 *   <li>自带随机盐，同一个密码每次编码结果都不同——数据库泄露后无法用彩虹表批量破解</li>
 *   <li>故意设计得**慢**（可调代价因子）。这正是密码哈希需要的：正常登录多花 100 毫秒
 *       无感，而暴力破解的尝试成本被抬高几个数量级。用 SHA-256 这类"快"哈希存密码是
 *       典型的错误</li>
 * </ul>
 *
 * <p><b>不要把这里的编码器拿去哈希别的数据</b>（比如缓存 Key、幂等标识）。
 * 它慢是特性不是缺陷，用在高频路径上会拖垮吞吐。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
