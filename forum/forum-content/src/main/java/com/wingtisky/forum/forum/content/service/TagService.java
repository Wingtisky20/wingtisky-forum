package com.wingtisky.forum.forum.content.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.forum.content.dto.TagView;
import com.wingtisky.forum.forum.content.entity.Tag;
import com.wingtisky.forum.forum.content.mapper.TagMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 标签的业务规则：取用或创建、与帖子建立/解除关联、维护使用次数。
 *
 * <p><b>标签由用户自由输入</b>（设计稿 §2.3），所以这里的复杂度几乎都来自两件事：
 * 并发创建同名标签、以及"看起来一样的名字"（大小写不同）。
 */
@Service
public class TagService {

    /** 一篇帖子最多带几个标签。超了返回 {@link ErrorCode#TOO_MANY_TAGS}。 */
    static final int MAX_TAGS_PER_POST = 5;

    /** 单个标签名的长度上限，与 {@code t_tag.name} 的 {@code varchar(32)} 一致。 */
    static final int MAX_NAME_LENGTH = 32;

    /** 标签列表一次最多返回多少个。 */
    static final int MAX_LIST_SIZE = 100;

    private final TagMapper tagMapper;

    public TagService(TagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    /** 热门标签：按使用次数倒序。 */
    public List<TagView> listTop(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIST_SIZE);
        return tagMapper.selectTop(safeLimit).stream()
                .map(TagView::from)
                .toList();
    }

    /**
     * 给帖子挂上这批标签（发帖时用）。
     *
     * <p>标签是"取用或创建"：已存在的直接用，没有的当场建一个。
     * 这与"预置标签表"是两条路——后者需要有人先维护那张表。
     */
    @Transactional
    public void attachTags(Long postId, List<String> rawNames) {
        for (String name : normalize(rawNames)) {
            Tag tag = findOrCreate(name);
            tagMapper.insertPostTag(postId, tag.getId());
            tagMapper.addPostCount(tag.getId(), 1);
        }
    }

    /**
     * 把帖子的标签整体换成这一批（改帖时用）。
     *
     * <p>**先全部摘掉、再挂新的**，而不是算差异去增删。
     * 算差异要处理"旧的有、新的没有""都有""都没有"三种情况，
     * 而这段代码的唯一价值是省几次数据库写入——换来的是三类分支的测试。
     */
    @Transactional
    public void replaceTags(Long postId, List<String> rawNames) {
        detachAll(postId);
        attachTags(postId, rawNames);
    }

    /**
     * 摘掉帖子的全部标签：**先把每个标签的使用次数减 1，再删关联**。
     *
     * <p>顺序不能反：关联删了之后就查不到"这篇帖子原来带了哪些标签"，
     * 那些标签的计数就永远减不回去了——标签页上会一直多着几篇。
     */
    @Transactional
    public void detachAll(Long postId) {
        List<Tag> attached = tagMapper.selectByPostId(postId);
        if (attached.isEmpty()) {
            return;
        }
        for (Tag tag : attached) {
            tagMapper.addPostCount(tag.getId(), -1);
        }
        tagMapper.deletePostTags(postId);
    }

    /**
     * 取用或创建。
     *
     * <p><b>并发下两个请求同时第一次用同一个标签</b>时会怎样：
     * 两边都查不到 → 两边都插入 → 第二个撞上 {@code name} 的唯一索引。
     * **这不是错误**，是"别人替我建好了"——回头重查一次拿他的用即可。
     *
     * <p>为什么不能"先查再插"就算并发安全：那中间有窗口。两个请求都查到"没有"，
     * 然后都插入，数据库会拦下一个，但**应用层已经以为自己是创建者**——
     * 于是可能出现两条同名标签（若没有唯一索引），或者一个没被处理的异常。
     */
    private Tag findOrCreate(String name) {
        Tag existing = tagMapper.selectByName(name);
        if (existing != null) {
            return existing;
        }

        Tag created = new Tag();
        created.setName(name);
        try {
            tagMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            Tag createdBySomeoneElse = tagMapper.selectByName(name);
            if (createdBySomeoneElse == null) {
                // 唯一索引冲突了、却又查不到这个名字——说明冲突来自别的约束
                // （比如将来加了别的唯一键）。**不能把这种情况吞掉**，
                // 否则真正的数据问题会变成一个安静的 200。
                throw e;
            }
            return createdBySomeoneElse;
        }
    }

    /**
     * 规范化标签名：去首尾空白、丢掉空的、**按大小写不敏感去重**、校验长度与数量。
     *
     * <p><b>去重必须大小写不敏感</b>，否则 {@code Redis} 与 {@code redis} 会同时通过，
     * 接着两条都要写进 {@code t_post_tag} —— 撞上联合主键抛异常，
     * 而那条异常长得完全不像"标签重复"，排查时会往别处找。
     * （数据库那边的唯一索引也是大小写不敏感的，两边判据必须一致。）
     *
     * <p>用 {@link Locale#ROOT} 而不是默认 Locale：土耳其语环境下
     * {@code "I".toLowerCase()} 得到的不是 {@code "i"}，会让去重结果随机器而变。
     *
     * @return 保序去重后的原始写法（**不转小写**——{@code Redis} 比 {@code redis} 好看）
     */
    static List<String> normalize(List<String> rawNames) {
        if (rawNames == null || rawNames.isEmpty()) {
            return List.of();
        }

        Map<String, String> byLowerName = new LinkedHashMap<>();
        for (String raw : rawNames) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 按**码点**数长度，与数据库的 varchar(32) 一致：
            // 一个 emoji 在 Java 里占两个 char，用 length() 会把它算成两个字符
            if (trimmed.codePointCount(0, trimmed.length()) > MAX_NAME_LENGTH) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "单个标签最长 " + MAX_NAME_LENGTH + " 个字符");
            }
            byLowerName.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }

        if (byLowerName.size() > MAX_TAGS_PER_POST) {
            throw new BizException(ErrorCode.TOO_MANY_TAGS);
        }
        return List.copyOf(byLowerName.values());
    }
}
