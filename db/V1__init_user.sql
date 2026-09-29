-- ============================================================
-- M1 · 用户域的建表脚本
--
-- 用法（先建库，见 docs/06-runbook/local-setup.md）：
--   mysql -u wingtisky -p wingtisky_forum      < db/V1__init_user.sql
--   mysql -u wingtisky -p wingtisky_forum_test < db/V1__init_user.sql
--
-- 本文件**不含建库与建账号语句**——那些需要 root 权限，且账号密码
-- 属于敏感信息，绝不入库（spec §10.9）。一次性步骤写在 runbook 里。
--
-- 设计依据：docs/03-design/m1-user-and-security.md §2
-- ============================================================

-- ------------------------------------------------------------
-- 用户表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(32)  NOT NULL COMMENT '登录名',
    password    VARCHAR(60)  NOT NULL COMMENT 'BCrypt 哈希，固定 60 字符。绝不明文，绝不进日志',
    nickname    VARCHAR(32)  NOT NULL COMMENT '昵称',
    avatar      VARCHAR(255)          DEFAULT NULL COMMENT '头像 URL（走本地存储，不做自建对象存储）',
    email       VARCHAR(64)           DEFAULT NULL COMMENT '邮箱，可空',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 / 1 已删',
    PRIMARY KEY (id),
    -- 唯一索引建在 username 单列上，**不带 deleted**。
    -- 后果：用户被逻辑删除后，其用户名不会释放（别人不能注册同名）。
    -- 这是有意的取舍——若把 deleted 加进索引，同一个用户名可以存在多条
    -- "已删除"记录，反而要靠额外的清理逻辑才能保证唯一；而"用户名不释放"
    -- 对一个技术社区来说完全可以接受（多数平台都是这么做的）。
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户';

-- ------------------------------------------------------------
-- 角色表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_role
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    code        VARCHAR(32) NOT NULL COMMENT '角色码：USER / MODERATOR / ADMIN',
    name        VARCHAR(32) NOT NULL COMMENT '角色名',
    description VARCHAR(128)         DEFAULT NULL COMMENT '说明',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色';

-- ------------------------------------------------------------
-- 用户-角色关联表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user_role
(
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    role_id BIGINT NOT NULL COMMENT '角色 ID',
    PRIMARY KEY (user_id, role_id),
    -- 联合主键已能支撑"按用户查角色"；这条索引是为了反过来查
    -- "这个角色下有哪些用户"（管理后台用），顺序相反，用不上主键索引
    KEY idx_role_id (role_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户-角色关联';

-- ------------------------------------------------------------
-- 初始化三个角色
--
-- 用 INSERT IGNORE 而不是 ON DUPLICATE KEY UPDATE：后者需要
-- `VALUES()` 或行别名语法，而 `VALUES()` 在 MySQL 8.0.20 起已废弃，
-- 用它会在启动时刷一条 deprecation 警告。这里只需要"没有就插入"，
-- INSERT IGNORE 更简单也更贴合意图。
-- ------------------------------------------------------------
INSERT IGNORE INTO t_role (code, name, description)
VALUES ('USER', '普通用户', '注册后的默认角色'),
       ('MODERATOR', '版主', '可管理内容'),
       ('ADMIN', '管理员', '全部权限');
