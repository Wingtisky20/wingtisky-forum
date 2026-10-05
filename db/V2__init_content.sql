-- ============================================================
-- M2 · 内容域的建表脚本
--
-- 用法（**开发库与测试库都要执行**）：
--   mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum      < db/V2__init_content.sql
--   mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum_test < db/V2__init_content.sql
--
-- ⚠️ **开发库与测试库都要跑**：只跑开发库的话，集成测试会因为"表不存在"而失败，
--    而那个报错不会提示你来执行这个脚本。
-- ⚠️ `--default-character-set=utf8mb4` 不能省。Windows 上 mysql 客户端的默认字符集是
--    **gbk**，而脚本文件是 UTF-8——不加这个参数时：**脚本执行成功、退出码 0、没有任何报错**，
--    但中文会被写坏（转不过去的字符变成 `?`，不可恢复）。
-- ⚠️ `V2__` 这种命名看起来像 Flyway，但本项目**没有引入 Flyway**（它不在技术栈白名单里）。
--    只是按那个约定命名，靠手工执行；后续里程碑沿用 `V3__`、`V4__`。
--
-- 本文件**不含建库与建账号语句**——那些需要 root 权限，且账号密码属敏感信息，
-- 绝不入库（spec §10.9）。一次性步骤写在 docs/06-runbook/local-setup.md。
--
-- 设计依据：docs/03-design/m2-content-core.md §2
-- 相关决策：ADR-0015（跨域取用户信息）、ADR-0016（计数冗余）、
--           ADR-0017（点赞收藏用专用表）、ADR-0018（评论两级）
--
-- 【两个全局约定，与 M1 的 V1 一致】
--   1. **不建外键**：跨表引用只是逻辑引用，一致性由业务保证
--   2. 表名 `t_` 前缀、utf8mb4、create_time/update_time 用默认值
--
-- 【排序规则】本文件不显式写 COLLATE，沿用服务器默认。
--   本机实测（2026-10-05）：`@@collation_server = utf8mb4_0900_ai_ci`，`ci` 即大小写不敏感。
--   **t_tag 依赖这一点**：`Redis` 与 `redis` 会撞 name 的唯一索引，被判为同一个标签。
--   这条依赖不是靠注释保证的——M2 Task 6 会用测试钉住它（两帖分别用 Redis / redis，
--   断言 t_tag 只有一行）。若哪天服务器排序规则换成大小写敏感的，测试会先炸。
-- ============================================================


-- ------------------------------------------------------------
-- 帖子
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_post
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    author_id     BIGINT       NOT NULL COMMENT '作者，逻辑引用 t_user.id（不建外键，见文件头）',
    title         VARCHAR(100) NOT NULL COMMENT '标题',
    content       MEDIUMTEXT   NOT NULL COMMENT '正文（Markdown 文本）。列表页不查这一列',
    summary       VARCHAR(255) NOT NULL COMMENT '列表页展示的摘要，发帖时截取',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 下架（版主隐藏，可恢复）',
    top_flag      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否置顶：0 否 / 1 是',
    featured_flag TINYINT      NOT NULL DEFAULT 0 COMMENT '是否加精：0 否 / 1 是',
    view_count    INT          NOT NULL DEFAULT 0 COMMENT '浏览数',
    like_count    INT          NOT NULL DEFAULT 0 COMMENT '点赞数（冗余计数，ADR-0016）',
    comment_count INT          NOT NULL DEFAULT 0 COMMENT '评论数（冗余计数，ADR-0016）',
    collect_count INT          NOT NULL DEFAULT 0 COMMENT '收藏数（冗余计数，ADR-0016）',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    -- deleted 与 status 是**两件事**，不要合并：
    --   deleted 管"这条记录还在不在"（作者删帖）
    --   status  管"这条记录能不能被看见"（版主下架，是治理动作，可撤销）
    -- 合成一个字段省不了多少事，代价是"版主误下架"和"作者删帖"再也分不开——恢复路径不同。
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 / 1 已删',
    PRIMARY KEY (id),

    -- 列表页主查询：WHERE deleted = 0 AND status = 0 ORDER BY top_flag DESC, create_time DESC
    -- 前两列都是**等值条件**且都进索引，所以"过滤 + 排序"能全靠索引完成，
    -- **不必回表**去检查 deleted —— 这正是 M6 深翻页要讲的那件事的前置。
    -- 不用 DESC：后两列的排序方向相同，升序索引**反向扫描**即可满足，DESC 是多余的。
    KEY idx_status_top_create (deleted, status, top_flag, create_time),

    -- 个人主页"他发的帖子"，按时间倒序
    KEY idx_author_create (author_id, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='帖子';


-- ------------------------------------------------------------
-- 标签
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_tag
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(32) NOT NULL COMMENT '标签名。唯一；大小写不敏感靠默认排序规则（见文件头）',
    post_count  INT         NOT NULL DEFAULT 0 COMMENT '该标签下的帖子数（冗余计数，ADR-0016）',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    -- 唯一索引不只是"防止重名"——并发下两个请求同时第一次用同一个新标签时，
    -- 靠它拦下第二条，应用层捕获唯一键冲突后回头重查即可。
    -- 换成"先查再插"在并发下有窗口：两个请求都查到"没有"，然后都插入。
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='标签';


-- ------------------------------------------------------------
-- 帖子-标签关联
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_post_tag
(
    post_id BIGINT NOT NULL COMMENT '帖子 ID',
    tag_id  BIGINT NOT NULL COMMENT '标签 ID',
    PRIMARY KEY (post_id, tag_id),
    -- 反查用了："这个标签下有哪些帖子"。联合主键的顺序是 (post_id, tag_id)，
    -- 查不了反方向（和 t_user_role 上那条 idx_role_id 是同一个理由）。
    KEY idx_tag_id (tag_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='帖子-标签关联';


-- ------------------------------------------------------------
-- 评论（两级：评论 + 回复）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_comment
(
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    post_id     BIGINT        NOT NULL COMMENT '所属帖子',
    author_id   BIGINT        NOT NULL COMMENT '评论者',
    parent_id   BIGINT        NOT NULL DEFAULT 0 COMMENT '直接父评论；**顶层评论为 0**',
    -- root_id 是"两级"能高效分页的关键：只按 parent_id 组织的话，
    -- 取"第 2 页的顶层评论"必须先知道哪些是顶层，而顶层判定要递归。
    root_id     BIGINT        NOT NULL DEFAULT 0 COMMENT '所属顶层评论；**顶层评论为 0**',
    content     VARCHAR(1000) NOT NULL COMMENT '内容',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 / 1 已删',
    PRIMARY KEY (id),
    -- 一个索引服务两个查询：
    --   ① 顶层评论分页：WHERE post_id = ? AND root_id = 0
    --   ② 某条顶层评论下的全部回复：WHERE root_id = ?
    KEY idx_post_root (post_id, root_id, id)
    -- 注意：本表**没有 like_count**。M2 范围里没有"评论点赞"，
    -- 留着那一列会是个没有写入方的悬空字段——读代码的人会去找谁在维护它，然后找不到。
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='评论（两级：评论 + 回复）';


-- ------------------------------------------------------------
-- 帖子点赞 / 帖子收藏
--
-- ⚠️ 这两张表是**全库唯一不带 deleted 的表**（t_user / t_post / t_comment 都有）。
--    不是漏了：「取消点赞」的语义是**这件事没发生过**，留一行 deleted = 1 没有意义；
--    而且唯一约束一旦配合逻辑删除，就变成"要保证有效行唯一"，得改用更绕的方案。
--    —— 见到它们没有 deleted 时，请回来看这段，不要以为是漏写。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_post_like
(
    user_id     BIGINT   NOT NULL COMMENT '点赞的人',
    post_id     BIGINT   NOT NULL COMMENT '被点赞的帖子',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
    -- 联合主键**就是幂等保证**：重复点赞会撞主键，配 INSERT IGNORE 即可，
    -- 不需要"先查有没有点过赞再插"——后者在并发下会插进两条。
    PRIMARY KEY (user_id, post_id),
    -- 查某帖的点赞数 / 哪些人点赞了（注意：计数另有冗余字段，这条是给人看的列表用）
    KEY idx_post_id (post_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='帖子点赞';

CREATE TABLE IF NOT EXISTS t_post_collect
(
    user_id     BIGINT   NOT NULL COMMENT '收藏的人',
    post_id     BIGINT   NOT NULL COMMENT '被收藏的帖子',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间（"我的收藏"按它倒序）',
    PRIMARY KEY (user_id, post_id),
    KEY idx_post_id (post_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='帖子收藏';
