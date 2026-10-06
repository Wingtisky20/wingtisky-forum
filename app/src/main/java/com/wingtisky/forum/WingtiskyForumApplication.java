package com.wingtisky.forum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开定时任务：M3 起有"浏览数定时回写数据库"（{@code PostViewCounter}）。
 *
 * <p>M0–M8 是单进程，所以这里只启用一次就够了；M9 拆成三个进程之后，
 * 每个进程各自跑自己的定时任务——**那时要留意的不是"会不会跑两遍"，
 * 而是"回写用的是绝对值、重复执行结果一样"**（回写之所以设计成幂等，
 * 正是为这一步留的余地）。
 */
@EnableScheduling
@SpringBootApplication
public class WingtiskyForumApplication {

    public static void main(String[] args) {
        SpringApplication.run(WingtiskyForumApplication.class, args);
    }
}
