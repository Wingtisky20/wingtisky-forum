# Wingtisky Forum — 项目设计规格

| 项 | 值 |
|---|---|
| 文档状态 | **待评审**（评审通过后改为「已冻结 v1.0」） |
| 日期 | 2026-09-13 |
| 项目根目录 | `D:\aaaDocuments\project\WingtiskyForum` |
| 目标仓库 | `https://github.com/Wingtisky20/wingtisky-forum`（Public） |
| 需求与决策 | Wingtisky |
| 架构与实现 | Claude（AI 协作） |

---

## 0. 这份文档是什么 / 怎么用

这是本项目的**权威设计规格**。它规定了"要做什么、为什么这么做、边界在哪"。

**怎么用**：

- **新对话接手**：不要通读本文。先读 `CLAUDE.md` → `docs/STATUS.md` → 本文件对应章节。`STATUS.md` 会指明当前里程碑需要哪几节。
- **发生分歧时**：以本文为准。如果要推翻本文的结论，**必须新增 ADR**（见 `docs/02-decisions/`），并在 `STATUS.md` 标注，禁止静默修改。
- **本文不包含**：数据库表结构（见 `docs/03-design/database.md`）、接口契约（见 `docs/03-design/api/`）。那是后续阶段的独立交付物。

---

## 1. 项目背景与目标

### 1.1 背景

本科在读，准备秋招，目标岗位 **Java 后端开发 / AI Agent 应用开发**。需要一个新的、从零构建的简历级项目。

已有的旧项目（`D:\aaaDocuments\aaaCommunity\community`，"仿牛客论坛二次开发"）**已完成**，本项目**不是它的复刻**：

- 旧项目**仅作只读参考**，**禁止修改**。它的价值在于沉淀了 5 大改造的面试素材与实测数据。
- 本项目要**重新推导一遍设计**——因为面试官问的是"你为什么这么设计"，而不是"你做了什么"。照搬等于把旧项目的坑再踩一遍。

### 1.2 目标

1. 产出一个**能讲清楚、经得起追问**的 Java 后端项目，并公开在 GitHub。
2. **5 个亮点必须真正做到**，每个都要有：设计文档 + 实现代码 + **实测数据** + 面试话术。
3. 完整走一遍真实工程流程：需求 → 建模 → 选型 → 库表 → 架构 → 接口 → 编码 → 测试 → 运维。
4. 在**同一个项目**上演进出 RAG / Agent 能力（阶段 M8），覆盖主流 AI 应用知识点。

### 1.3 5 个必须落地的亮点

| # | 亮点 | 核心内容 |
|---|---|---|
| 1 | 高频读 | Caffeine(L1) → Redis(L2) → MySQL，Redis Pub/Sub 跨节点失效，Redisson 分布式锁防击穿 |
| 2 | 异步写 | Kafka 自定义分区 + 手动 Ack + Redis 幂等去重 |
| 3 | 检索优化 | ES 检索 + Kafka 增量同步 + 定时全量兜底 + IK 分词器扩展 |
| 4 | DB 治理 | 延迟关联（Deferred Join）解决深翻页 |
| 5 | 安全限流 | Spring Security RBAC + Redis ZSet 滑动窗口限流 |

### 1.4 成功标准

- [ ] 5 个亮点全部落地，**每个都有 `[REAL]` 级实测数据**与反面对照
- [ ] GitHub 公开仓库，README 能让陌生人在干净环境按文档跑起来
- [ ] 每个里程碑有 tag，可对照、可回滚
- [ ] 每个亮点有 `docs/05-interview/` 的逐行讲解 + 自测问题清单
- [ ] 你能对每个亮点的**关键设计决策**用大白话讲出"为什么"

### 1.5 明确不做的事（YAGNI）

这份清单与"要做什么"同等重要，是抵抗范围蔓延的依据：

- ❌ 微服务 / 注册中心 / 网关 / 分布式事务 / 分布式 ID
- ❌ **分库分表** —— 本地单机、数据量不支持；且做了分表就无法讲 Deferred Join，自相矛盾
- ❌ 自建对象存储（图片走本地存储 + 预留接口，不搭 MinIO 集群）
- ❌ 推荐算法（首页用 时间 + 热度分，不做协同过滤）
- ❌ IM 长连接（私信做拉取式基础版，不上 WebSocket）
- ❌ 多级审核工作流、复杂权限矩阵
- ❌ 全量 DDD 落地（领域事件、聚合根全套）
- ❌ Kafka Streams / Flink 流处理
- ❌ 引入任何额外框架/工具（遵循 **minimal tooling** 原则）

---

## 2. 关键决策摘要

> 完整的 ADR（背景 / 候选方案 / 决定 / 后果）在 `docs/02-decisions/`。此处只列结论。
>
> **本表是增量增长的**：下表是截至 2026-09-13 的决策。随着项目推进，各里程碑会持续新增 ADR（例如 M1 的限流 fail-open 决策、M3 的缓存一致性权衡、M2 的持久层选型）。`docs/02-decisions/README.md` 是**权威索引**，与本表不一致时以索引为准。

| 编号 | 决策 | 结论 | 理由摘要 |
|---|---|---|---|
| ADR-0001 | 技术栈基线 | JDK 17 + Spring Boot 3.x + ES 8.x + Kafka 3.9(KRaft)；MySQL 8.0 / Redis 5 沿用 | 后续 Spring AI / LangChain4j 硬性要求 Java 17+，避免返工 |
| ADR-0002 | 架构形态 | 模块化单体（Maven 多模块 + 领域边界） | 拿到"服务划分规范"的可讲点，又不付分布式事务的代价 |
| ADR-0003 | 前端形态 | Vue3 + Vite + Element Plus，前后端分离 | 国内 Java 岗接受度最高；为 M8 的对话界面留位 |
| ADR-0004 | 不使用 Docker（本地） | 本机原生部署中间件 | 16G/6 核 + Win10 Home 无 WSL2，叠虚拟化会拖垮开发体验 |
| ADR-0005 | 不使用微服务 | 明确不拆 | 本机资源不支持；且非本项目亮点，属过度设计 |
| ADR-0006 | 交付形态 | GitHub 公开 + 本地可复现；不做公网部署 | 时间投向业务与亮点，文档质量本身即加分项 |
| ADR-0007 | 集成测试不跑 CI | 本地中间件 + `@Tag("integration")` 隔离 | 本机无 Docker，无法用 Testcontainers；限制与替代方案写入 ADR |
| ADR-0008 | 分支模型 | GitHub Flow（非 Git Flow） | 单人项目，轻量且为业界主流；PR 作为变更记录载体 |

---

## 3. 技术栈基线

| 层 | 选型 | 版本 | 说明 |
|---|---|---|---|
| 语言 | Java | **17 LTS**（本机已装 17.0.16） | JDK 21 是候选升级项，见 §12 开放问题 |
| 框架 | Spring Boot | **3.x** | 强制 `jakarta.*` 包名 |
| 安全 | Spring Security | **6.x** | 使用 `SecurityFilterChain` Bean，**禁止** `WebSecurityConfigurerAdapter`（已删除） |
| 持久层 | MyBatis / MyBatis-Plus + MySQL | **8.0.41**（沿用本机） | 版本统一收在父 POM `dependencyManagement` |
| 缓存 | Redis | **5.0.14.1**（沿用本机） | Windows 原生受限于此版本，取舍见 ADR-0004 |
| 分布式锁 | Redisson | 3.x | 版本需与实际 Redis 5 兼容，接入前核实 |
| 消息 | Kafka | **3.9.x，KRaft 模式** | **不再需要 ZooKeeper 进程**，本机更轻 |
| 检索 | Elasticsearch | **8.x** | 用官方 **Java API Client**；**禁止** TransportClient（ES 8 已移除） |
| 分词 | IK 分词器 | 与 ES 8 对应版本 | 含自定义扩展词典 |
| 本地缓存 | Caffeine | 最新稳定版 | L1 |
| 前端 | Vue3 + Vite + Element Plus | — | 独立工程 |
| 构建 | Maven（多模块） | 本机已装 | 父 POM 统一版本管理 |
| 运行时 | **不使用 Docker** | — | 见 ADR-0004 |

### 3.1 幻觉高发区（接入时强制流程）

以下组件版本跨度大，模型极易写出过时 API。**接入时必须先查官方文档或本地 jar 源码，再写代码**，禁止凭记忆：

| 高危区 | 典型错误 |
|---|---|
| Spring Security 6 | 写已删除的 `WebSecurityConfigurerAdapter` |
| Spring Boot 3 | `javax.*` 未迁移为 `jakarta.*` |
| ES 8 Java API Client | 沿用 ES 6/7 的 `TransportClient` / 老 `QueryBuilder` |
| Kafka 3.x KRaft | 混用 ZooKeeper 时代的配置项 |
| Redisson | API 跨版本差异大 |

---

## 4. 架构设计

### 4.1 模块划分

```
wingtisky-forum/
├─ forum-common/          统一响应体、错误码、异常体系、通用工具、注解
├─ forum-domain/          领域模型、DTO/VO、模块间对外接口（契约）
├─ forum-infra/           中间件基础设施：连接配置、序列化、Key 规范、重试、客户端生命周期
├─ forum-module-user/     用户域：注册登录、鉴权、个人主页
├─ forum-module-content/  内容域：帖子、评论、标签、专栏
├─ forum-module-search/   检索域：ES 索引管理、增量/全量同步、搜索
├─ forum-module-notify/   通知域：系统通知、点赞/评论提醒
├─ forum-module-admin/    后台管理
├─ forum-boot/            启动装配，唯一含 main() 的模块
├─ frontend/              Vue3 独立工程
├─ scripts/               造数、对账、中间件启停
└─ tools/                 防漂移脚本（check-doc-refs / check-deps）
```

### 4.2 边界规则（强制）

1. `forum-common` **不依赖任何业务模块**，不含业务逻辑。
2. `forum-domain` 只放**契约**（模型 + 接口），**不放实现**。
3. 业务模块**只能**依赖 `common` / `domain` / `infra`，**不得依赖其他业务模块的 internal 包**。
4. `forum-boot` 是唯一有 `main()` 的模块。

**规则不靠自觉**：用 Maven 依赖约束 + **ArchUnit 测试**把它变成构建期/测试期能自动拦截的规则。

### 4.3 关键边界线：机制 vs 策略

- `forum-infra` 负责**机制**：连接、序列化、Key 命名规范、重试策略、客户端生命周期。
- 业务模块负责**策略语义**：缓存存多久、哪些事件要幂等、索引怎么映射。

**为什么这样切**：若策略也塞进 `infra`，`infra` 会变成全知上帝模块；若业务模块到处直接 `redisTemplate.opsForValue()`，中间件会渗透进每一行业务代码，将来换实现要改上百处。**这条线画在「机制 vs 策略」上**，是最经得起追问的切法。

---

## 5. 五大亮点链路设计

### 5.1 亮点 1 · 高频读：Caffeine(L1) → Redis(L2) → MySQL

**读路径**：
```
Caffeine → miss → Redis → miss → Redisson RLock(按 key) → 锁内 double-check
        → 回源 MySQL → 写回 L2 + L1 → 释放锁
```

| 问题 | 做法 |
|---|---|
| 击穿 | Redisson `RLock` + **锁内 double-check**（拿到锁后必须重新查缓存，否则锁形同虚设） |
| 穿透 | 空值墓碑（存特殊标记 + **短 TTL**，避免长期占用内存） |
| 雪崩 | TTL 加随机抖动 |
| 跨节点失效 | Redis Pub/Sub 广播失效 → 各节点清本地 Caffeine |
| 写一致性 | Cache-Aside：**先更新 DB，再删缓存**（不是更新缓存） |

**已知缺陷与兜底**：Pub/Sub **不可靠**——无持久化，订阅者掉线期间消息直接丢失。因此 **L1 的 TTL 设短（30–60 秒）** 作为兜底：即使失效消息丢失，脏数据最多存活一个 TTL。

> 备选方案「epoch 版本号失效」将在 ADR 中作为**被评估过的替代方案**记录。

**实测指标**：缓存命中率 · P99 延迟（纯 MySQL vs 三级缓存）· 并发 100 请求验证仅 1 次回源。

---

### 5.2 亮点 2 · 异步写：Kafka 自定义分区 + 手动 Ack + Redis 幂等

**生产端**：以 `entityId` 为 key 发送 → 自定义 `Partitioner` 按 entityId 哈希 → **同一实体的事件落同一分区** → 分区内有序。
配置：`acks=all` + `retries` + `enable.idempotence=true`；发送失败回调 → 落重试队列。

**消费端**：`enable-auto-commit=false` + `AckMode.MANUAL_IMMEDIATE`，**业务成功才 ack**。

**幂等**：每条消息带全局 `eventId`，消费前 `SETNX` 占位，已存在则跳过。

**死信**：重试 N 次仍失败 → 投递死信队列 + 告警日志，**不无限重试阻塞分区**。

> **关键认知（必须能讲清）**：Kafka 的「幂等生产者」**只保证单个生产者会话内、单分区不重复**，跨会话（重启、分区重分配）不保证。所以「恰好一次」不是 Kafka 给的，而是 **至少一次投递 + 消费端幂等** 拼出来的。

**实测指标**：同一 `eventId` 重复投递 → 业务只执行一次；消费异常不 ack → 验证会重投。

---

### 5.3 亮点 3 · 检索：ES 8 + 增量同步 + 全量兜底 + IK 扩展

**索引与分词**：IK（`ik_max_word` 建索引 / `ik_smart` 查询）+ **自定义扩展词典**（技术社区有大量专有名词：Spring Boot、Kafka、Redisson、Caffeine…）+ **同义词**（"ES / Elasticsearch"、"多级缓存 / 三级缓存"）。

**增量同步**：内容变更 → Kafka 事件 → 消费 → ES upsert。**直接复用亮点 2 的 Kafka 链路**——同一 topic 挂两个消费组（通知组 / 索引组），而非另写一套。

**全量兜底**：定时任务分页扫 DB 与 ES 比对补齐。**禁止「覆盖式重建」**（会让线上搜索短暂不可用），采用**别名切换（alias）**或增量补齐。

**对账**：独立的一致性校验任务（count 比对 + 抽样内容 hash 比对）。

**实测指标**：对账通过率 · 搜索 P99 · **IK 扩展前后的召回对比**。

---

### 5.4 亮点 4 · DB 治理：Deferred Join 解深翻页

**原理**：`LIMIT 100000, 20` 会扫描 100020 行再丢弃 10 万行。Deferred Join 让内层**走覆盖索引只取 id**（扫描量骤降），外层再用 id 回表取完整行。

**前提条件（必须诚实面对）**：**排序列必须有索引**。若内层也是 filesort，Deferred Join 反而更慢——**它不是银弹**。

**两套分页并存**：
- 需要页码跳转的列表页 → Deferred Join
- 无限滚动信息流 → **Keyset（游标）分页**，更快但不能跳页

**实测方法**：造 10 万 / 100 万行数据，对比 `EXPLAIN` 扫描行数与实际耗时曲线。**必须如实记录「浅翻页时 Deferred Join 反而更慢」的结论**——诚实的负面结论在面试中是加分项，因为它证明你真的测过。

---

### 5.5 亮点 5 · 安全限流：Spring Security 6 RBAC + Redis ZSet 滑动窗口

**鉴权**：Spring Security 6 `SecurityFilterChain` Bean + RBAC + 方法级 `@PreAuthorize`。

**资源级鉴权（IDOR 防护）**：RBAC 只管「你是不是管理员」，管不了「这条私信是不是你的」。旧项目曾实测出**私信可被越权查看**的漏洞。本项目用注解 + AOP 做资源归属校验。

**限流**：Redis ZSet + **Lua 脚本保证原子性**（查计数 / 清过期 / 新增 / 设 TTL 必须在同一原子操作内，否则并发下计数不准）。

**为什么用 ZSet 而非固定窗口计数器**：固定窗口有**边界突刺**——窗口切换前后各打满，瞬时流量达阈值 2 倍。此对比将做成实验数据。

**fail-open 决策**：Redis 不可用时**放行（fail-open）**而非拒绝——论坛属非关键业务，可用性优先于限流，但须打告警日志。此决策进 ADR。

---

### 5.6 跨亮点设计（重要）

五个亮点**不是五套平行代码**，存在两处真实耦合：

1. **亮点 2 的事件总线同时服务亮点 3**：一个 topic、两个消费组（通知组 / 索引组），而非为 ES 另写同步逻辑。
2. **亮点 1 与亮点 2 的一致性权衡**：发帖后缓存如何失效？
   - **同步删除**：强一致，但写路径耦合缓存
   - **等 Kafka 事件异步失效**：解耦，但有延迟窗口，靠 TTL 兜底
   - → 必须选边站，写成 ADR。

---

## 6. 业务范围与里程碑

### 6.1 产品定位

**技术社区**（对标掘金 / 牛客），内容以**长文技术分享**为主。选择理由：

- 内容形态（长文本 + 标签 + 评论）天然适配亮点 3（ES 全文检索）与 M8 的 RAG，**不硬凑**
- 业务模型成熟，无需在需求探索上耗时
- 技术社区的用户即程序员，面试官可代入

### 6.2 里程碑

| 里程碑 | 内容 | 预估 | 闸门（不达标不算完成） |
|---|---|---|---|
| **M0** 启动与基建 | GitHub 仓库、多模块骨架、CLAUDE.md / STATUS / ADR 机制、CI 骨架、**ES8 + Kafka3.9 安装**、目录骨架、术语表 | 1 周 | `mvn compile` 过 · CI 绿 · ES/Kafka **启动验证** `[REAL]` |
| **M1** 用户域与安全基座 | 注册登录、个人主页、角色；Spring Security 6 + JWT + 统一响应/异常/日志；**亮点 5 落地** | 3 周 | `m1-done` · 限流实测 `429` · **越权访问实测被拒** |
| **M2** 内容域核心 | 帖子、标签、评论、点赞收藏、后台；Vue3 工程 + 核心页面 | 4 周 | 真实 HTTP 走通 `发帖→评论→列表` |
| **M3** 亮点 1 多级缓存 | Caffeine + Redis + Redisson 锁 + Pub/Sub + 墓碑 + TTL 抖动 | 2.5 周 | 命中率/延迟对比 · **100 并发仅 1 次回源** |
| **M4** 亮点 2 Kafka 异步写 | 自定义分区 + 手动 Ack + 幂等 + 死信 | 2.5 周 | **重复投递只执行一次** · 消费失败重投实证 |
| **M5** 亮点 3 ES 检索 | 索引 + IK 扩展词 + 增量（复用 M4 链路）+ 全量兜底 + 对账 | 3.5 周 | **对账通过** · IK 前后召回对比 |
| **M6** 亮点 4 深翻页 | Deferred Join + Keyset 双方案 + 造数 + EXPLAIN 对比 | 1.5 周 | 10w/100w 行实测 · **含「浅翻页反而更慢」结论** |
| **M7** 质量与交付 | 集成测试、压测报告、可观测性、CI 完整化、README | 3 周 | `m7-done` · **干净环境按 README 能跑起来** |
| **M8** AI 演进 | RAG / 向量检索 / 工具调用 / 多轮对话（**当新需求重走完整流程**） | 4-6 周 | 检索评测集 · 对话链路可用 |

**合计约 20-25 周**（AI 协作会压缩实现时间）。

**估算诚实声明**：以上数字在当前为**预估值**。**M1 结束时必须用实测速度重新校准 M2-M8**，并把校准结果写入 `STATUS.md`。不抱着假精确的计划走半年。

**排序依据**：
- 亮点 5 在 M1 —— **安全是基座**，其他功能接口都要过它
- 亮点 3 在 M5 —— **依赖 M4 的 Kafka 链路**（复用事件总线）
- 亮点 4 在 M6 —— **需要数据量**才有意义，需先有造数脚本
- M8 最后 —— **依赖 M5 的 ES 检索能力**

### 6.3 M8 的预留（现在只留位，不实现）

M8 的形态**现在不设计**，只保证当前设计不堵死它：

- 内容域的长文本（帖子正文）可被导出用于向量化
- 检索域（`forum-module-search`）是独立模块，M8 的语义检索可在此扩展，**不污染 `content`**
- 分层清晰，引入 AI 能力时不需要重构业务代码

---

## 7. 测试策略

| 层 | 范围 | 工具 | 跑在哪 |
|---|---|---|---|
| 单元测试 | 领域规则、限流算法、幂等判断 | JUnit 5 + AssertJ + Mockito | 本地 + **CI** |
| 集成测试 | Service 层真实读写 DB/Redis/Kafka/ES | Spring Boot Test + **本地中间件** | **仅本地**（`@Tag("integration")`） |
| 接口测试 | Controller 真实 HTTP | MockMvc + `.http` 文件 | 本地 |
| 端到端 | 真实链路（发帖→Kafka→ES→搜索命中） | `.http` 脚本 + 手工 | 本地 |
| 压测 | 亮点性能数据 | JMeter / wrk | 本地 |

### 7.1 测试的定位

**测试不是「证明我对了」，而是「证明我没撒谎」。** 三条硬规则：

1. **造数脚本进 `scripts/` 并版本控制** —— 任何人都能复现数据
2. **每个亮点必须有正例 + 反例**，且**反例优先**：先写"不做优化时会怎样"，再写"做了之后怎样"
3. **端到端必须用真实数据**，禁止用 mock 假装端到端（「验证剧场」的典型形态）

### 7.2 已知限制（诚实记录）

社区标准做法是 Testcontainers（测试时临时拉起真实中间件），**但本机无 Docker，无法运行**。因此集成测试连接本机已装中间件（独立 test 库/索引），**代价是 CI 中无法运行集成测试**。

此限制写入 ADR-0007，并附「如果有 Docker 应该怎么做」。GitHub Actions runner 本身有 Docker，M7 可评估「CI 中用 Testcontainers 跑集成测试」——但那会导致**本地复现不了 CI 的失败**，故 M7 再决策，现在不引入。

---

## 8. 工程协作机制（跨对话无缝衔接）

### 8.1 目录结构

```
WingtiskyForum/
├─ CLAUDE.md                    ★ 项目宪法（每次对话自动加载）
├─ README.md                    ★ 门面：简介 + 架构图 + 如何跑起来
├─ pom.xml                        聚合 POM
├─ docs/
│  ├─ STATUS.md                 ★ 唯一状态源
│  ├─ 00-charter/
│  │   ├─ charter.md              章程
│  │   └─ collaboration.md        协作协议
│  ├─ 01-requirements/
│  │   ├─ prd.md                  需求规格（EARS 风格验收标准）
│  │   ├─ domain-model.md         领域模型
│  │   └─ glossary.md           ★ 术语表（防 AI 中途换词）
│  ├─ 02-decisions/               ADR，README.md 为索引
│  ├─ 03-design/
│  │   ├─ architecture.md
│  │   ├─ database.md
│  │   ├─ api/                    接口契约
│  │   └─ roadmap-to-microservices.md  演进预案
│  ├─ 04-journal/YYYY-MM-DD.md    工作日志
│  ├─ 05-interview/             ★ 每个亮点：逐行讲解 + 实测数据 + 话术
│  ├─ 06-runbook/
│  │   ├─ local-setup.md
│  │   └─ troubleshooting.md    ★ 踩过的坑
│  ├─ assets/
│  │   ├─ images.md             ★ 每张图的 AI 生成提示词
│  │   └─ diagrams/               Mermaid 源文件
│  └─ superpowers/specs/          本文档所在
├─ forum-common/  forum-domain/  forum-infra/
├─ forum-module-user/  forum-module-content/  forum-module-search/
├─ forum-module-notify/  forum-module-admin/  forum-boot/
├─ frontend/                      Vue3 独立工程
├─ scripts/                       造数、对账、中间件启停
└─ tools/                         check-doc-refs / check-deps
```

**设计细节**：
- `docs/` 用**数字前缀**（00/01/02…）—— **阅读顺序即接手顺序**，新对话按编号读即可。
- `05-interview/` 与 `06-runbook/troubleshooting.md` 单独成目录 —— 面向的是人（你和面试官），混在技术文档中会被淹没。

### 8.2 会话协议

**开场**（用户只需说「读文档，继续」）：

按固定顺序读 **3-4 个文件**：
1. `CLAUDE.md`（自动加载）
2. **`docs/STATUS.md`** —— 唯一入口，其中写明"当前上下文文件"清单
3. `docs/00-charter/collaboration.md` —— 协议本身

然后**必须先向用户复述**：当前阶段 → 上次做到哪 → 本次要做什么 → 有哪些阻塞。**用户确认后才动手。**

> 这一步是防「AI 自信地跑偏」的关键闸门。漂移症状（中途换约定、推荐不在技术栈上的库、重新实现已有工具类、与早先决策矛盾）全都发生在「没有复述确认就直接开干」的对话里。

**收场**（必须做完五件事）：

1. 更新 `STATUS.md`（进度 + **证据等级** + 下一步 + 阻塞）
2. 写 `docs/04-journal/YYYY-MM-DD.md`
3. 有新决策 → 写 ADR
4. `git commit`（Conventional Commits + 里程碑前缀）
5. **向用户报告**：本次真实改了什么、验证到什么程度、遗留什么

### 8.3 三条防矛盾铁律

| 铁律 | 说明 |
|---|---|
| **单一事实源** | 状态只在 `STATUS.md`；需求只在 PRD；决策只在 ADR。别处**只引用、不复制**——复制必然导致两份打架 |
| **变更留痕** | 推翻旧结论 → **必须新增 ADR** + 在 `STATUS.md` 标注。**禁止静默修改** |
| **里程碑闸门** | 每个里程碑结束打 tag（`m1-done`），可回滚、可对照、可讲 |

### 8.4 上下文卫生

- **一个对话一件事**。一个功能做完就收场，不在同一对话里连做三个（「context collapse」的成因）
- 对话变长时**主动建议开新对话**，而非硬撑到输出质量下降
- `CLAUDE.md` 中维护**技术栈白名单**，任何不在名单上的依赖必须先问用户

> 方法论题眼：**「永远不要问 AI '你怎么实现的'，而要告诉 AI '我们的架构约定是什么'。」**

---

## 9. 防幻觉机制（六道闸门）

### 闸门 1 · 证据制

**铁律：任何「已完成 / 已通过 / 已修复」的结论，必须附带可复现的命令 + 真实输出。**

`STATUS.md` 中每条标注证据等级：

| 标记 | 含义 |
|---|---|
| `[REAL]` | 真实链路跑过，附命令与输出 |
| `[SYNTHETIC]` | 只用造的数据或单测跑过，**未经真实链路** |
| `[UNVERIFIED]` | 只是写完了，没验证 |
| `[BLOCKED]` | 卡住，附原因 |

### 闸门 2 · 反「验证剧场」

- 端到端验证**必须用真实数据**：真实 HTTP 请求（curl / `.http`）、真实 DB 行、真实 Kafka 消息
- 每个亮点的实测数据**必须给出反面对照**（优化前 vs 优化后）。**单边数据不算证据**

### 闸门 3 · 依赖防幻觉

任何新依赖必须：① 在 Maven Central 真实存在（**去查，不凭记忆**）② 版本核对 ③ `mvn dependency:tree` 解析成功 ④ 版本统一收在父 POM `<dependencyManagement>`。

**禁止**「我记得有个库叫 xxx」这类句式。package hallucination 是安全危害最高的一类幻觉。

### 闸门 4 · 提交信息与真实 diff 对账

**提交前**把 `git diff` 拉出来，逐条对照 commit message 声称的内容。声称了但没做的，要么补上，要么改说明。

> 依据：2026 年一项对 23,247 个 AI 提交 PR 的研究显示，**45.4% 存在"说明里声称的改动未实现"**。

### 闸门 5 · 幻觉高发区点名

见 §3.1。接入高危组件时**先读官方文档或本地 jar 源码，再写代码**；接入后第一关是**启动验证**。

### 闸门 6 · 文档-代码对账脚本

`tools/` 下两个脚本，**进 CI**：

- `check-doc-refs` —— 扫描文档中出现的类名/方法名/配置项（反引号内容），验证其在代码中**真实存在**，防止文档漂移到"描述一个不存在的类"
- `check-deps` —— 验证所有依赖真实可解析（闸门 3 的自动化）

> **只加这两个脚本，不引入任何额外框架**（遵循 minimal tooling）。

---

## 10. Git / GitHub 工程规范

### 10.1 仓库与提交身份

| 项 | 值 |
|---|---|
| 仓库 | `https://github.com/Wingtisky20/wingtisky-forum` |
| 可见性 | **Public** |
| 主分支 | `main`（**永恒可运行、可演示**） |
| License | MIT |
| 提交邮箱 | **GitHub noreply**（保护隐私，仍能正确关联贡献图） |
| 换行符 | `.gitattributes` 中 `* text=auto`（Windows 开发 + GitHub 协作必需） |

> **待修复**：本机 `git config --global user.name` / `user.email` 为空。M0 第一件事修复，否则提交作者信息错误或提交失败。

### 10.2 分支模型：GitHub Flow

```
main                          ← 永远可运行，禁止直接 push
 ├─ feature/m1-jwt-auth
 ├─ fix/m3-cache-stampede
 ├─ docs/adr-0007
 └─ chore/upgrade-es8
```

**流程**：切分支 → 小步提交 → **开 PR（哪怕自己合并）** → 自查清单 → merge → 删分支。

**为什么单人项目也走 PR**：PR 描述本身就是**结构化的变更记录**（做了什么 / 为什么 / 怎么验证的 / 有什么风险），是「可追溯、可讲述」的载体。

### 10.3 提交信息规范

Conventional Commits + 里程碑 scope：

```
<type>(<scope>): <subject>

<body —— 写"为什么"，不写"改了什么">

<footer —— Refs: ADR-xxxx / Closes #12>
```

`type`：`feat` `fix` `docs` `refactor` `perf` `test` `chore` `build` `ci` `revert`
`scope`：如 `m1-user`、`infra`、`m3-cache`

**铁律**：body 写**为什么**，不写**改了什么** —— 后者 `git diff` 自己会说。

### 10.4 回滚规范

| 场景 | 手段 | 关键说明 |
|---|---|---|
| 撤销最近提交（**未推送**） | `git reset --soft HEAD~1` | 保留改动，重新组织 |
| 撤销**已推送**的提交 | `git revert <sha>` | **生成反向提交**，不改写历史 |
| 回退整个里程碑 | `git revert <merge-sha> -m 1` 或 checkout tag | 里程碑有 tag 锚点 |

**两条铁律**：
1. **`main` 上永不 force push**（公开仓库改写历史会破坏所有引用，是真实事故）。force push 只允许用于自己的 feature 分支。
2. **每次回滚必须留痕**：在 `docs/04-journal/` 写清「为什么回滚、回滚到什么状态、后续怎么办」。

> 回滚记录本身就是面试素材——「我做过一次回滚，因为 X，回滚后改成了 Y」比「一路顺利」可信得多。

### 10.5 Tag 与 Release

每个里程碑打**注解 tag**：`git tag -a m1-done -m "..."` 并 push。GitHub 上发 Release（notes 从 `docs/04-journal/` 汇总）。

好处：随时 `git diff m2-done m3-done` 即可看到「亮点 1 到底加了什么」——**面试前复盘靠这个**。

### 10.6 CI

`.github/workflows/ci.yml`，push / PR 触发：

```
JDK 17 编译 → 单元测试 → check-deps → check-doc-refs
```

集成测试**不在 CI 中运行**（原因见 §7.2 与 ADR-0007）。

### 10.7 敏感信息铁律

**密钥、密码绝不入库。**

- 敏感配置走**环境变量**，代码中只读 `${DB_PASSWORD}`
- 提供 `.env.example`（只有占位符），`.env` 进 `.gitignore`
- **万一误提交**：① 立刻改密码 / 吊销密钥 ② `git filter-repo` 清洗历史 ③ force push。三步缺一不可（只删文件再提交，历史里仍在）
- `.gitignore`：`target/`、`.idea/`、`node_modules/`、`*.log`、`.env`、中间件数据目录

---

## 11. 风险与应对

| 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|
| 环境卡住（ES8 / Kafka3.9 on Win10） | 中 | 高 | M0 第一件事；**卡超 1 天即降级**（ES 7.17） |
| 时间超支 | 高 | 中 | M1 结束校准；**每个里程碑独立可讲**，中途停也有成果 |
| 亮点做出来但无实测数据 | 中 | 高 | 实测数据是闸门硬条件，不达标不算完成 |
| 上下文漂移 / AI 幻觉 | 中 | 高 | 六道闸门 + 会话协议 |
| **用户读不懂 AI 写的亮点代码** | 中 | **最高** | `05-interview/` 逐行讲解 + 自测问题清单 |

**最后一条影响最高**：本项目**唯一不可替代的产出是用户的理解**。代码可重写、文档可补，但面试官问的每句话都得用户自己答。

### 11.1 人工深度参与的边界

| 类别 | 谁主写 | 用户责任 |
|---|---|---|
| CRUD、DTO、Mapper、前端页面、配置、文档 | **AI 全量生成** | 能读懂、能改 |
| 5 大亮点核心文件、鉴权与资源归属校验 | **AI 写** | **逐行读懂 + 能用大白话复述** |

用户选择 **（b）宽松模式**：AI 产出讲解文档，用户自行阅读，不阻塞开发进度。每个亮点完成时 AI 提供**自测问题清单**（不给答案），用户自测，答不上来再补讲。

---

## 12. 待确认与开放问题

以下为**尚未决策**的事项，各自归属的决策时点：

| # | 开放问题 | 决策时点 |
|---|---|---|
| 1 | 社区命名、主题、Logo、配色方案 | M0 / M1，由 AI 提供 2-3 个组合方案供选 |
| 2 | JDK 17（已装）vs JDK 21（虚拟线程是加分点） | M0 前 |
| 3 | 鉴权方案：JWT vs Spring Security Session | M1 设计时（ADR） |
| 4 | 亮点 1 与亮点 2 的一致性权衡：同步失效 vs 异步失效 | M3（ADR） |
| 5 | 图片是否生成 AI 提示词；架构图是否用 Mermaid（已倾向 Mermaid） | M0 |
| 6 | 是否需要可视化伴侣（浏览器中展示 UI 稿 / 架构图） | 首次出现视觉问题时 |
| 7 | CI 中是否用 Testcontainers 跑集成测试 | M7 |
| 8 | M8 的 AI 演进形态（Java 侧 Spring AI / LangChain4j vs 独立 Python 服务） | M7 末 |
| 9 | 持久层：MyBatis vs MyBatis-Plus（§3 表中暂并列） | M2 设计时（ADR） |
| 10 | 私信模块是否纳入范围（§1.5 已定"拉取式基础版"；是否在 M2 内实现待定） | M2 规划时 |

---

## 附录 A · 本次会话的决策轨迹

| 顺序 | 问题 | 用户决策 |
|---|---|---|
| 1 | 技术栈基线 | 现代栈：JDK17 + SB3 + ES8 + Kafka 3.9(KRaft) |
| 2 | 前端形态 | Vue3 + Vite + Element Plus |
| 3 | 时间预算 | 4-6 个月 |
| 4 | 产品定位 | 技术社区（对标掘金/牛客） |
| 5 | 交付要求 | GitHub 公开 + 本地可复现 |
| 6 | 架构形态 | 方案 A：模块化单体 |
| 7 | 复述要求 | （b）宽松模式 |
| 8 | 仓库名 | `wingtisky-forum` |
| 9 | 提交邮箱 | GitHub noreply |

### 环境实测记录（2026-09-13）

| 项 | 实测值 |
|---|---|
| 内存 / CPU | 16,505,954,304 B（≈15.4 GiB）/ AMD Ryzen 5 4500U，6 核 6 线程 |
| 磁盘 | C: 剩 ~19 GB（**不可用于安装**）/ D: 剩 ~229 GB |
| JDK | 17.0.16 LTS（已在 PATH） |
| 中间件 | MySQL 8.0.41（运行中）· Redis 5.0.14.1 · Kafka 2.8.1(+ZK) · ES 6.8.23，均在 `D:\aaaSoftware` |
| Docker / WSL | **均未安装** |
| git / gh | git 2.53.0 · gh 2.98.0 · 已登录 `Wingtisky20`（scope: gist, read:org, repo） |
| **git 身份** | **未配置**（user.name / user.email 为空）→ M0 修复 |
| IDE / Node | IntelliJ IDEA Community 2025.2.6.2 · Node（`D:\aaaSoftware\nodejs`）· Maven |
