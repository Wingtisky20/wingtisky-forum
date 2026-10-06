package com.wingtisky.forum.forum.content.service;

import com.wingtisky.forum.common.exception.BizException;
import com.wingtisky.forum.common.result.ErrorCode;
import com.wingtisky.forum.forum.content.dto.TagView;
import com.wingtisky.forum.forum.content.entity.Tag;
import com.wingtisky.forum.forum.content.mapper.TagMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TagService} 的单元测试。
 *
 * <p>标签由用户自由输入，所以这里的坑集中在"两个看起来不一样、实际是同一个"的名字上：
 * {@code Redis} 与 {@code redis} 在数据库看来是一个标签，若 Java 这边按原样去重，
 * 两条都会通过，然后一起往下走。
 *
 * <p>{@code normalize} 是包级可见的静态方法，**可以直接测**——
 * 那些规则（去重、长度、数量）都是纯函数，不必绕经数据库。
 */
@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Mock
    private TagMapper tagMapper;

    private TagService tagService;

    @BeforeEach
    void setUp() {
        tagService = new TagService(tagMapper);
    }

    private static Tag tag(long id, String name) {
        Tag t = new Tag();
        t.setId(id);
        t.setName(name);
        t.setPostCount(0);
        return t;
    }

    @Nested
    @DisplayName("标签名规范化")
    class Normalize {

        @Test
        @DisplayName("★ 大小写不敏感去重：Redis 与 redis 只留一个，保留先出现的那个写法")
        void deduplicatesCaseInsensitively() {
            // 若按原样去重，两个都会留下 —— 随后两条都要写进 t_post_tag，
            // 撞上联合主键抛异常，而那条异常长得不像"标签重复"
            assertThat(TagService.normalize(List.of("Redis", "redis", "REDIS")))
                    .containsExactly("Redis");
        }

        @Test
        @DisplayName("去首尾空白、丢掉空的和 null，且保持原有顺序")
        void trimsAndDropsBlanks() {
            // ⚠️ 这里必须用 Arrays.asList 而不是 List.of：后者不接受 null 元素，
            // 而 element 为 null 恰恰是**真实会发生的输入**（前端传的 JSON 数组里
            // 可以带 null，比如 ["java", null]）。用 List.of 会先抛 NPE，
            // 让这条测试在"还没测到被测逻辑"时就挂了。
            assertThat(TagService.normalize(Arrays.asList("  java ", "", "   ", null, "mysql")))
                    .containsExactly("java", "mysql");
        }

        @Test
        @DisplayName("超过 5 个 → A0203")
        void rejectsTooManyTags() {
            assertThatThrownBy(() -> TagService.normalize(
                    List.of("a", "b", "c", "d", "e", "f")))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                            .isEqualTo(ErrorCode.TOO_MANY_TAGS));
        }

        @Test
        @DisplayName("去重之后正好 5 个是允许的（先去掉重复再判数量）")
        void deduplicatesBeforeCounting() {
            assertThat(TagService.normalize(
                    List.of("a", "A", "b", "c", "d", "e")))
                    .containsExactly("a", "b", "c", "d", "e");
        }

        @Test
        @DisplayName("单个标签超过 32 个字符 → A0101")
        void rejectsTooLongName() {
            String tooLong = "x".repeat(33);

            assertThatThrownBy(() -> TagService.normalize(List.of(tooLong)))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                            .isEqualTo(ErrorCode.PARAM_INVALID));
        }

        @Test
        @DisplayName("正好 32 个字符是允许的（边界不差一）")
        void acceptsExactlyMaxLength() {
            String exactly32 = "x".repeat(32);

            assertThat(TagService.normalize(List.of(exactly32)))
                    .containsExactly(exactly32);
        }

        @Test
        @DisplayName("null 与空列表都返回空，不抛异常")
        void toleratesEmptyInput() {
            assertThat(TagService.normalize(null)).isEmpty();
            assertThat(TagService.normalize(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("取用或创建")
    class FindOrCreate {

        @Test
        @DisplayName("标签已存在 → 直接用，不再插入")
        void usesExistingTag() {
            when(tagMapper.selectByName("java")).thenReturn(tag(1L, "java"));

            tagService.attachTags(9L, List.of("java"));

            verify(tagMapper, never()).insert(any());
            verify(tagMapper).insertPostTag(9L, 1L);
            verify(tagMapper).addPostCount(1L, 1);
        }

        @Test
        @DisplayName("标签不存在 → 新建一个再挂上")
        void createsMissingTag() {
            when(tagMapper.selectByName("newtag")).thenReturn(null);
            // insert 之后主键会被回填（XML 配了 useGeneratedKeys），这里模拟那个行为
            when(tagMapper.insert(any())).thenAnswer(inv -> {
                inv.getArgument(0, Tag.class).setId(42L);
                return 1;
            });

            tagService.attachTags(9L, List.of("newtag"));

            verify(tagMapper).insertPostTag(9L, 42L);
            verify(tagMapper).addPostCount(42L, 1);
        }

        @Test
        @DisplayName("★ 并发：插入时撞唯一索引 → 回头重查，用别人建好的那个（不报错）")
        void recoversFromConcurrentCreation() {
            Tag createdByOther = tag(7L, "race");
            when(tagMapper.selectByName("race"))
                    .thenReturn(null)          // 第一次：还没人建
                    .thenReturn(createdByOther); // 冲突之后重查：已经有了
            doThrow(new DuplicateKeyException("uk_name"))
                    .when(tagMapper).insert(any());

            tagService.attachTags(9L, List.of("race"));

            // 关键：没有把异常抛出去，而是拿到了别人建的那个标签
            verify(tagMapper).insertPostTag(9L, 7L);
            verify(tagMapper).addPostCount(7L, 1);
        }

        @Test
        @DisplayName("★ 唯一索引冲突了却查不到这个名字 → 把异常抛出去，不吞")
        void rethrowsWhenConflictIsNotAboutName() {
            when(tagMapper.selectByName("weird")).thenReturn(null);
            doThrow(new DuplicateKeyException("别的约束"))
                    .when(tagMapper).insert(any());

            // 吞掉的话，真正的数据问题会变成一个安静的 200
            assertThatThrownBy(() -> tagService.attachTags(9L, List.of("weird")))
                    .isInstanceOf(DuplicateKeyException.class);

            verify(tagMapper, never()).insertPostTag(anyLong(), anyLong());
        }

        @Test
        @DisplayName("一次挂多个标签：每个都建立关联、各自 +1")
        void attachesEveryTag() {
            when(tagMapper.selectByName("java")).thenReturn(tag(1L, "java"));
            when(tagMapper.selectByName("mysql")).thenReturn(tag(2L, "mysql"));

            tagService.attachTags(9L, List.of("java", "mysql"));

            verify(tagMapper).insertPostTag(9L, 1L);
            verify(tagMapper).insertPostTag(9L, 2L);
            verify(tagMapper).addPostCount(1L, 1);
            verify(tagMapper).addPostCount(2L, 1);
        }
    }

    @Nested
    @DisplayName("摘标签与替换")
    class DetachAndReplace {

        @Test
        @DisplayName("★ 先减计数、再删关联（顺序反了就永远减不回去了）")
        void decrementsBeforeDeletingLinks() {
            when(tagMapper.selectByPostId(9L)).thenReturn(List.of(tag(1L, "java")));

            tagService.detachAll(9L);

            InOrder ordered = inOrder(tagMapper);
            ordered.verify(tagMapper).addPostCount(1L, -1);
            ordered.verify(tagMapper).deletePostTags(9L);
        }

        @Test
        @DisplayName("没有标签时什么都不做，不去发删除语句")
        void doesNothingWhenNoTags() {
            when(tagMapper.selectByPostId(9L)).thenReturn(List.of());

            tagService.detachAll(9L);

            verify(tagMapper, never()).deletePostTags(anyLong());
            verify(tagMapper, never()).addPostCount(anyLong(), anyInt());
        }

        @Test
        @DisplayName("整体替换 = 先全摘掉，再挂新的")
        void replaceDetachesThenAttaches() {
            when(tagMapper.selectByPostId(9L)).thenReturn(List.of(tag(1L, "old")));
            when(tagMapper.selectByName("new")).thenReturn(tag(2L, "new"));

            tagService.replaceTags(9L, List.of("new"));

            InOrder ordered = inOrder(tagMapper);
            ordered.verify(tagMapper).deletePostTags(9L);
            ordered.verify(tagMapper).insertPostTag(9L, 2L);
            verify(tagMapper, times(1)).addPostCount(1L, -1);
        }
    }

    @Nested
    @DisplayName("标签列表")
    class ListTop {

        @Test
        @DisplayName("limit 上限被夹到 100")
        void clampsLimit() {
            when(tagMapper.selectTop(TagService.MAX_LIST_SIZE)).thenReturn(List.of());

            tagService.listTop(100_000);

            verify(tagMapper).selectTop(TagService.MAX_LIST_SIZE);
        }

        @Test
        @DisplayName("limit 小于 1 时夹到 1")
        void clampsLimitToAtLeastOne() {
            when(tagMapper.selectTop(1)).thenReturn(List.of());

            tagService.listTop(0);

            verify(tagMapper).selectTop(1);
        }

        @Test
        @DisplayName("把实体转成返回形状")
        void mapsToView() {
            when(tagMapper.selectTop(50)).thenReturn(List.of(tag(1L, "java")));

            List<TagView> result = tagService.listTop(50);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("java");
        }
    }
}
