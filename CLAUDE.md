# Wingtisky Forum — 项目宪法

> 本文件被 Claude Code **每次对话自动加载**，是接手的第一个入口。
> 当前状态见 **`docs/STATUS.md`**。完整协作流程见 **`docs/00-charter/collaboration.md`**。

---

## 项目是什么

**深栈** —— 技术社区 + 付费内容（参照 CSDN 形态），含优惠券秒杀。
Java 后端秋招简历项目。仓库：https://github.com/Wingtisky20/wingtisky-forum

**三个业务域**：内容域（帖子/专栏/检索/通知）· 交易域（商品/订单/支付/解锁）· 秒杀域（券库存）
**服务形态**：3 个服务。**M0–M8 单进程（逻辑态），M9 才物理拆分**。

**不是**旧项目（`D:\aaaDocuments\aaaCommunity\community`）的复刻。旧项目**只读参考，禁止修改**。

## 权威文档顺序（冲突时以此为准）

1. `docs/03-design/architecture.md` —— 架构
2. `docs/02-decisions/` —— 决策记录（ADR）
3. `docs/superpowers/plans/` —— 实施计划
4. `docs/superpowers/specs/2026-09-13-WingtiskyForum-design.md` —— 规格

## 技术栈白名单

**任何不在下表的技术/依赖，必须先问用户，不得擅自引入。**

| 层 | 选型 |
|---|---|
| 语言 / 框架 | Java 17 · Spring Boot **3.5.16** |
| 安全 | Spring Security 6（`SecurityFilterChain` Bean，**禁止** `WebSecurityConfigurerAdapter`） |
| 数据库 | MySQL 8.0（沿用本机） |
| 缓存 | Redis 5.0.14.1（沿用）· Redisson **3.52.0** · Caffeine **3.2.4** |
| 消息 | Kafka **3.9.x KRaft**（**无 ZooKeeper**） |
| 检索 | Elasticsearch **8.x** + IK（用 Java API Client，**禁止** TransportClient） |
| 服务治理 | Nacos · Spring Cloud Gateway（**M9 才引入**） |
| 前端 | Vue3 + Vite + Element Plus |
| 构建 | Maven 多模块 |
| 测试 | JUnit 5 · ArchUnit **1.5.0** |

### 明确禁止（§1.5「不做的事」）

❌ Docker · ❌ 微服务全家桶 · ❌ **Sentinel**（会替代掉自己实现的限流亮点）· ❌ **Seata**（用本地消息表 + 最终一致）· ❌ RocketMQ · ❌ 分库分表 · ❌ Kafka Streams/Flink · ❌ 任何未列入白名单的框架

**版本核查必须用 `maven-metadata.xml`**，不要用 solr 的 `core=gav&rows=N`（按发布时间排序，会返回过期版本）。

## 模块结构

```
wt-common / wt-domain / wt-infra      共享层
forum/  (user, content, search, notify)     内容域
trade/  (product, order, payment)           交易域
seckill/ (coupon)                           秒杀域
app/                                        唯一启动模块（M9 拆为 forum-app/trade-app/seckill-app + gateway）
```

**边界由 ArchUnit 强制**：三个域两两不得互相依赖，只能通过 `wt-domain` 的接口通信。

**边界线画在「机制 vs 策略」**：`wt-infra` 只管连接、序列化、Key 规范、重试；缓存存多久、哪些事件幂等、索引怎么映射，属业务模块。

## 会话协议

**开场** —— 用户说「读文档，继续」时：
1. 本文件（自动加载）
2. `docs/STATUS.md` —— 唯一状态源，其中指明「当前上下文文件」
3. `docs/00-charter/collaboration.md`

**然后必须先向用户复述**：当前阶段 → 上次做到哪 → 本次要做什么 → 有哪些阻塞。**用户确认后才动手。**

**收场** —— 五件事缺一不可：
1. 更新 `docs/STATUS.md`（进度 + 证据等级 + 下一步 + 阻塞）
2. 更新 `docs/04-log/` 当前阶段文档
3. 有新决策 → 写 ADR
4. `git commit`（严格格式，见下）
5. **向用户报告**：真实改了什么、验证到什么程度、遗留什么

## 用户参与机制（**优先级高于任何执行技能**）

**判据「面试可讲性」**：面试官可能追问"为什么这么做"的决策，**用户必须参与**；纯机械改动不必打扰。

> ⚠️ `subagent-driven-development` 等技能默认「任务之间不停下确认」。**该默认在本项目失效。**

**每个任务动手前，Step 0 必须先向用户说清三件事，等回应才动手：**
① 这步做什么 ② 为什么现在做、不做会怎样 ③ 有哪几种做法 / 建议哪种 / 代价是什么

**不替用户拍板**：影响设计的决定须写明「我建议 X，因为 Y」；用户未表态时**不得静默执行**。

## Git 标准

**分支**：GitHub Flow。`main` 永远可运行，**禁止直接 push**；走 `feature/mX-*` → PR → **Merge commit（`--no-ff`）**。
（2026-09-19 修订：原定 Squash，因它会把迭代过程压没，见 ADR-0008。）

**提交信息格式（四节必填）**：
```
<type>(<scope>): <subject>

【为什么】    ← 必填。写不出"为什么"，说明这个提交不该存在
【改了什么】  ← 人的意图摘要，不是 diff 的复述
【验证】      ← 必填。附**实际执行的命令与真实结果**，不接受"应该没问题"
【关联】      ← Refs: ADR-xxxx
```
`type`：`feat` `fix` `docs` `refactor` `perf` `test` `chore` `build` `ci` `revert`
`scope`：业务用 `m<里程碑>-<域>`（如 `m3-cache`），基建用 `infra`/`build`/`ci`/`docs`

**提交频率：一个「功能」应产生 3-8 个提交，不是一个。** 一个工作日通常应有多次提交。

- 拆分层级（按需选用）：骨架 → 接口契约 → 领域实现 → 装配 → 测试
- **修复独立成条**，永不混进功能提交
- 测试独立成条；文档不与代码混提
- **拆分信号**：`【改了什么】` 里出现「并」「和」「顺便」「同时」连接两件不相关的事
- **高频 ≠ 提交半成品**：进入 `main` 的每个提交都必须可编译；半成品用 `wip:` 留在功能分支

**提交前逐条对照 `git diff`**：声称了却没做的改动，是最常见也最该被抓的失真。

**回滚**：`main` 上**永不 force push**。已推送的用 `git revert`（生成反向提交）。**每次回滚必须在 `docs/04-log/` 留痕**——回滚记录本身就是面试素材。

**Tag**：每个里程碑打注解 tag（`m1-done`），可对照、可回滚。

## 防幻觉六道闸门

1. **证据制** —— 结论必附命令 + 真实输出；`STATUS.md` 标注 `[REAL]`/`[SYNTHETIC]`/`[UNVERIFIED]`/`[BLOCKED]`
2. **反验证剧场** —— 端到端必须真实数据；亮点实测必须有**反面对照**（单边数据不算证据）
3. **依赖防幻觉** —— 新依赖必须 Maven Central 真实存在 + `mvn dependency:tree` 可解析；**禁止**"我记得有个库叫…"
4. **提交与 diff 对账** —— 提交前逐条对照
5. **高危区点名** —— Spring Security 6 / Boot 3 的 `jakarta.*` / ES 8 Java API Client / Kafka KRaft / Redisson：**先读官方文档或本地 jar 源码，再写代码**
6. **文档-代码对账** —— `tools/check-doc-refs.sh` 与 `tools/check-deps.sh` 进 CI

## 环境事实

- 16G 内存 · Ryzen 5 4500U（6 核）· **C 盘仅剩 ~19G，安装一律 D 盘**
- JDK 17.0.16 · Maven / Node 在 `D:\aaaSoftware`
- 中间件在 `D:\aaaSoftware`：MySQL 8.0.41（已装）· Redis 5.0.14.1（已装）· **Kafka 3.9 / ES 8.18.3 待装**
- **无 Docker、无 WSL2** —— 集成测试连本地中间件，不在 CI 跑
- **git 访问 GitHub 必须走代理**：本机 Clash Verge 混合端口 `127.0.0.1:18569`，已通过 `git config --local` 配置（**Clash 未运行时 git push 会失败，不是仓库坏了**）。详见 `docs/06-runbook/troubleshooting.md`
- 仓库地址：https://github.com/Wingtisky20/wingtisky-forum（Public）

## 文字风格（2026-09-13 用户要求）

**给人和面试官看的文字要有人味**：自然、严谨，**允许举例**。

- **不要公文腔**。"赋能""抓手""闭环""综上所述"这类词，出现一次就该删掉
- **不要为了显得专业而堆术语**。术语第一次出现时要解释，别假设读者知道
- **例子比定义好懂**。讲抽象概念时给一个具体场景，胜过三行定义
- 分对象：**面向机器/未来的文字**（commit、ADR）要精确、可检索；**面向人和面试官的**（README、执行记录、讲解文档）要通畅、像人写的

## 上下文卫生

- **一个对话一件事**。一个功能做完就收场，不在同一对话连做三个
- 对话变长时**主动建议用户开新对话**，而非硬撑到输出质量下降
- 永远不要问「你怎么实现的」，而要告诉下游「我们的约定是什么」
