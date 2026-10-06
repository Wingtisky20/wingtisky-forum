package com.wingtisky.forum;

import com.wingtisky.forum.forum.content.entity.Post;
import com.wingtisky.forum.forum.content.mapper.PostMapper;
import com.wingtisky.forum.infra.cache.LocalCache;
import com.wingtisky.forum.infra.cache.TwoLevelCache;
import com.wingtisky.forum.infra.redis.RedisKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 闸门实验各组的共用夹具：一样的中间件、一样的热集、一样的清理。
 *
 * <p><b>为什么要抽出一个基类</b>：两组的可比性完全建立在"只差一个开关"上。
 * 热集差一篇、清理少一步，比出来的差值就说不清了。所以夹具只有这一份，
 * 子类唯一的差别是 {@code @SpringBootTest} 上那个属性。
 *
 * <p>热集的帖子**直接走 Mapper 插入**，不走发帖接口——那要先去登录拿令牌，
 * 而令牌与本次实验要量的事情毫无关系。这是夹具，不是"真实链路"，
 * 真实链路由 {@code TestRestTemplate} 打出去的那些请求承担。
 */
abstract class DetailReadBenchmarkSupport {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TwoLevelCache cache;

    @Autowired
    protected LocalCache localCache;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected PostMapper postMapper;

    /** 本次实验的热集（大家反复看的那一小撮帖子）。 */
    protected final List<Long> hotIds = new ArrayList<>();

    @BeforeEach
    void createHotSet() {
        hotIds.clear();
        for (int i = 0; i < DetailReadBenchmark.HOT_SET; i++) {
            Post post = new Post();
            post.setAuthorId(1L);
            post.setTitle("基准热集帖 " + i);
            post.setContent("基准用的正文。" + i);
            post.setSummary("基准用的正文。" + i);
            postMapper.insert(post);
            hotIds.add(post.getId());
        }
    }

    @AfterEach
    void cleanUp() {
        for (Long id : hotIds) {
            String key = RedisKey.cachePostDetail(id);
            redis.delete(key);
            localCache.invalidate(key);
            // 测试库是可抛弃的，但留一堆垃圾行会让下次跑实验的热集编号一路涨
            postMapper.softDelete(id);
        }
        hotIds.clear();
    }
}
