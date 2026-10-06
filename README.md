# 深栈 · Wingtisky Forum

一个技术社区：写文章、发专栏，专栏可以卖，优惠券限量抢。

> 这是我在准备秋招期间从零构建的 Java 后端项目。**它不只是 CRUD** —— 里面有真实的高并发场景（秒杀）、真实的分布式问题（跨节点缓存失效、跨服务一致性），以及每一个设计决策背后的取舍记录。

---

## 项目状态

**🚧 M2 · 内容域核心 + 前端（收尾中）**

| 阶段 | 状态 |
|---|---|
| 需求 / 技术选型 / 架构设计 | ✅ 完成 |
| M0 项目启动与协作基建 | ✅ 完成 |
| M1 用户域与安全基座 | ✅ 完成 —— 含**亮点 5**：Redis ZSet 滑动窗口限流（含按接口分类的降级） |
| M2 内容域核心 + 前端 | 🚧 **收尾中** —— 帖子 / 评论 / 标签 / 点赞收藏 / 后台治理 + Vue3 前端 |
| M3 多级缓存 · M4 Kafka · M5 ES · M6 深翻页 | ❌ 尚未开始 |
| M7 交易域 · M8 秒杀 · M9 服务化 · M10 压测交付 | ❌ 尚未开始 |

**现在已经能在本地跑起来了**（后端 + 前端），见下面的 [本地运行](#本地运行)。

**验收闸门**：M2 的闸门是「真实 HTTP 走通 `发帖 → 评论 → 列表`」——**已达成**，
并附了反面对照（未登录发帖 401、改别人的帖子 403、重复点赞计数不变、跨帖回复 400）。
后续里程碑的闸门见 `docs/STATUS.md`。

> 你可以先看看 `docs/` —— 那里面才是这个项目最有价值的部分：**每个技术决策都记录了当时有哪些候选方案、为什么选了它、代价是什么**。
> 想知道"某条链路到底怎么走"，看 `docs/05-interview/`——那是从 HTTP 一路讲到数据库的逐行讲解。

---

## 三个业务域

```
内容域 forum     用户 / 帖子 / 评论 / 专栏 / 检索 / 通知
交易域 trade     商品（付费专栏） / 订单 / 支付 / 内容解锁
秒杀域 seckill   优惠券库存 / 原子扣减 / 异步下单 / 防刷
```

它们不是拼在一起的。一条完整链路把它们串起来：

```
写帖子 ──┐
        ├─→ 专栏设为付费 → 上架为商品 → 发券 → 秒杀抢券
写专栏 ──┘                                        ↓
                                            创建订单 → 支付 → 解锁内容
```

**为什么这样设计**：一个业务域该不该加，判断标准不是"技术含量高不高"，而是"**它和现有域有没有真实的数据往来**"。没有往来的域放在一起只是拼贴，不是系统。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 框架 | Java 17 · Spring Boot 3.5.16 |
| 安全 | Spring Security 6 · JWT |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis · Redisson · Caffeine |
| 消息 | Kafka 3.9（KRaft，**无需 ZooKeeper**） |
| 检索 | Elasticsearch 8 + IK 分词 |
| 前端 | Vue3 · Vite · Element Plus |
| 构建 | Maven 多模块 |

---

## 架构

```
wt-common / wt-domain / wt-infra     共享层
forum/   (user, content, search, notify)   内容域
trade/   (product, order, payment)         交易域
seckill/ (coupon)                          秒杀域
app/                                       启动模块
```

**边界怎么保证**：模块间只能通过 `wt-domain` 暴露的接口通信，不能碰对方内部实现——这条规则由 **ArchUnit 测试强制**，违反就构建失败，不是靠自觉。

**一条关键的分界线**：

> `wt-infra` 只管**机制**（连接怎么建、Key 怎么命名、失败重试几次）；
> 业务模块管**策略**（这个缓存存多久、哪些事件要幂等、索引怎么映射）。

切在这里的原因：如果策略也塞进 `wt-infra`，它会变成一个什么都懂的上帝模块；反过来如果业务代码里到处直接写 `redisTemplate.opsForValue()`，中间件就渗透进了每一行业务逻辑，将来换实现要改上百处。

---

## 打算做的 8 个技术点

| # | 技术点 | 难点在哪 |
|---|---|---|
| 1 | 多级缓存 Caffeine → Redis → MySQL | 缓存击穿、穿透、雪崩的分别处理；Pub/Sub 失效**会丢消息**，需要兜底 |
| 2 | Kafka 异步写 | 自定义分区保持同实体有序；手动 Ack；**消费端幂等**（"恰好一次"从来不是 Kafka 给的） |
| 3 | ES 检索与同步 | IK 扩展词典；增量 + **定时全量兜底**；不能覆盖式重建 |
| 4 | 深翻页治理 | 延迟关联；**诚实结论：浅翻页时它反而更慢** |
| 5 | RBAC + 滑动窗口限流 | ZSet + Lua 原子性；**资源级鉴权**（RBAC 管不了"这条私信是不是你的"） |
| 6 | 秒杀一致性 | Lua 原子扣减防超卖；异步下单削峰；失败要**库存回补** |
| 7 | 分布式锁 | 跨实例互斥 |
| 8 | 最终一致性 | 本地消息表 + 定时对账。**只讲 MQ 不讲补偿，等于只讲了一半** |

这些不是背下来的名词。每个都有实测数据、有对照实验，也有**被否决的方案**——那些记录往往比结论本身更有意思。

---

## 本地运行

**前置**：JDK 17 · Maven · Node 18+ · MySQL 8（Windows 服务，常驻）· Redis 5。

本项目**不用 Docker**（ADR-0004），中间件都装在本机。

### 1. 起中间件

```
# MySQL：装好后是 Windows 服务，通常已经在跑
# Redis：手动起
redis-server.exe
```

> **M2 只需要这两个。** ES 与 Kafka 要到 M3 / M4 / M5 才用得上，现在不用起。

### 2. 建库与建表

（**建库与建账号需要 root 权限，是一次性操作**，见 [`docs/06-runbook/local-setup.md`](docs/06-runbook/local-setup.md)）

```
mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum      < db/V1__init_user.sql
mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum      < db/V2__init_content.sql
mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum_test < db/V1__init_user.sql
mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum_test < db/V2__init_content.sql
```

⚠️ 三件事都不能省，**而且它们出问题时都不会报错**：

- **`--default-character-set=utf8mb4`**：Windows 上 mysql 客户端的默认字符集是 **gbk**，
  而脚本文件是 UTF-8。不加这个参数时脚本**执行成功、退出码 0、没有任何报错**，
  但中文会被写坏（转不过去的字符变成 `?`，**不可恢复**）
- **开发库与测试库都要建**：只建一个的话，集成测试会因为缺表而失败，
  而那个报错不会提示你来跑这个脚本
- `V1__` / `V2__` 这种命名**看起来像 Flyway，但本项目没有引入 Flyway**，
  只是按那个约定命名，靠手工执行

### 3. 配置环境变量

```
cp .env.example .env
```

填上 `DB_PASSWORD` 与 `JWT_SECRET`（后者用 `openssl rand -base64 48` 生成，
HS256 要求至少 32 字节）。**`.env` 已在 `.gitignore` 里，不会入库。**

### 4. 起后端

```
mvn -pl app -am package -DskipTests
set -a && source .env && set +a
java -jar app/target/app-1.0.0-SNAPSHOT.jar
```

后端在 `http://localhost:8080`。

> 在 IDEA 里也可以直接跑 `WingtiskyForumApplication`，
> 把 `.env` 里的键值填进 Run Configuration 的环境变量即可。

### 5. 起前端

```
npm --prefix frontend install
npm --prefix frontend run dev
```

打开 `http://localhost:5173`。

> **前端不需要单独配后端地址**：Vite 的 dev server 把 `/api` 代理到 8080，
> 在浏览器看来前后端是同源的（见 `frontend/vite.config.js` 里的说明）。

### 6. 验证真的跑通了

按 [`docs/06-runbook/api-smoke.http`](docs/06-runbook/api-smoke.http) 从第 1 条往下跑
（IDEA 直接点箭头，VS Code 装 REST Client 插件也行）。

它不只是"能通"——**每条都写了期望值，而且自带反面对照**：
未登录发帖应当是 401、改别人的帖子应当 403、重复点赞计数不该变、跨帖回复应当 400。
**只有"被拒绝"没有"该通过的通过了"，不算证据。**

跑一遍单元测试（**不连数据库**，任何机器上都能跑）：

```
mvn -B test
```

末尾出现 `BUILD SUCCESS` 就是全过。

---

## 文档导航

| 想了解 | 看这里 |
|---|---|
| 项目要做什么、不做什么 | [`docs/00-charter/charter.md`](docs/00-charter/charter.md) |
| 架构与设计取舍 | [`docs/03-design/architecture.md`](docs/03-design/architecture.md) |
| **为什么这么选**（含被否决的方案） | [`docs/02-decisions/`](docs/02-decisions/) |
| **某条链路怎么走**（从 HTTP 到数据库，逐行） | [`docs/05-interview/`](docs/05-interview/) |
| 当前进度与待办 | [`docs/STATUS.md`](docs/STATUS.md) |
| 环境怎么搭 | [`docs/06-runbook/local-setup.md`](docs/06-runbook/local-setup.md) |
| 踩过的坑 | [`docs/06-runbook/troubleshooting.md`](docs/06-runbook/troubleshooting.md) |

---

## License

MIT
