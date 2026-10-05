package com.wingtisky.forum.forum.content.controller;

import com.wingtisky.forum.common.result.Result;
import com.wingtisky.forum.forum.content.dto.TagView;
import com.wingtisky.forum.forum.content.service.TagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签接口。
 *
 * <p><b>只有"取"没有"建"</b>：标签不是先建好再用的资源，它是**发帖时顺带产生的**——
 * 用户想用哪个标签就直接写，后端替他取用或创建（{@code TagService.attachTags}）。
 * 单独开一个"新建标签"接口会让前端多一步，而用户根本不知道自己的标签是不是已存在。
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /**
     * 热门标签。**公开接口**。
     *
     * <p>不分页：标签的总量不大，而且这里给的是"按热度排的前 N 个"，
     * 翻到第 20 页的热门标签没有实际场景。上限由 {@code TagService} 夹紧。
     */
    @GetMapping
    public Result<List<TagView>> list(@RequestParam(defaultValue = "50") int limit) {
        return Result.success(tagService.listTop(limit));
    }
}
