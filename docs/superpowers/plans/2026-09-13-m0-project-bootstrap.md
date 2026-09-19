# M0 · 项目启动与协作基建 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Wingtisky Forum 项目具备"能开工、可交接、可追溯"的全部前提——**三个业务域、12 个模块**的 Maven 骨架能编译启动、协作机制就位（含用户确认闸门与执行记录）、本地中间件环境就绪、代码在 GitHub 上、CI 是绿的。

**工期：** 1.5 周（spec §6.2）。较初版估的 1 周上调——模块数由 9 增到 12，且多出 `forum/`、`trade/`、`seckill/` 这一层聚合（构建配置与验证的工作量随层数增长）。

**Architecture:** Maven 多模块的**逻辑态**（一个进程，但模块边界已按三个服务划好，architecture.md §3.1）：三个共享模块 `wt-common` / `wt-domain` / `wt-infra` + 三个业务域聚合 `forum/`（`forum-user`/`forum-content`/`forum-search`/`forum-notify`）、`trade/`（`trade-product`/`trade-order`/`trade-payment`）、`seckill/`（`seckill-coupon`）+ **`app` 单进程启动模块（唯一 `main()`）**。父 POM 统一版本管理，域间边界由 Maven 依赖约束 + ArchUnit 测试强制（`forum/**`、`trade/**`、`seckill/**` 两两不得互相依赖）。**M9 才做物理拆分**——`forum-app` / `trade-app` / `seckill-app` / `gateway` 在 M0 阶段**不存在**。本里程碑**不接入任何中间件依赖**——Redis/Kafka/ES 的 starter 在各自里程碑（M3/M4/M5）按需引入，遵循 YAGNI。

**Tech Stack:** Java 17 · Spring Boot 3.5.16 · Maven 多模块（三域 12 模块）· ArchUnit 1.5.0 · GitHub Actions

## Global Constraints

以下为项目级硬约束，**每个任务都隐式包含**：

- **Java 17**（本机 17.0.16 已装）。**禁止 `javax.*` 包名**，一律 `jakarta.*`。
- **Spring Boot 3.5.x 线的最新补丁版**（2026-09-13 实测为 **3.5.16**）。**版本一律用 `maven-metadata.xml` 核实，不要用 solr 的 `core=gav` top-N 查询**——后者按发布时间排序而非版本号，会返回过期版本（本计划初版即因此把 3.5.3 误判为最新）。**不采用 4.x 线**（理由见 ADR-0001）。
- **禁止使用 Docker**（本机 16G/6 核、Win10 Home 无 WSL2）。
- **所有安装必须落在 D 盘**。C 盘仅剩 ~19G，**严禁**写入。
- **禁止直接 push `main`**。所有改动走 feature 分支 + PR + Squash merge。
- **提交信息必须符合 spec §10.3 严格格式**（\【为什么\】\【改了什么\】\【验证\】\【关联\】）。
- **敏感信息绝不入库**。密码/密钥走环境变量，提供 `.env.example` 占位模板。
- **Minimal tooling**：不引入本计划未列出的任何框架、插件或工具。
- **用户确认闸门（spec §8.5，优先级高于任何执行技能的默认值）**：**每个任务动手前**（含本计划的每一个 Task），必须先向用户用人话说清三件事并**等用户回应才动手**：① 这步做什么 ② 为什么现在做、不做会怎样 ③ 有哪几种做法、我建议哪种、代价是什么。`subagent-driven-development` 等技能默认"任务之间不停下确认"——**该默认在本项目失效**。故每个任务均以 `Step 0: 向用户确认` 开头，**任何任务不得从 Step 1 直接开始**。
- **执行记录（spec §8.6）**：M0 结束时在 `docs/04-log/` 产出 `01-M0-项目启动与协作基建.md`，**精炼优先、宁少勿多**（结构：一句话总结 / 产出了什么 / 关键决策与理由 / 你可以怎么验证 / 踩过的坑 / 遗留与风险 / 回滚记录）。**本计划的 `docs/04-log/` 全面取代旧计划的 `04-journal/`（按日期）方案**——两套并存必然打架，违反单一事实源。
- **提交拆分（spec §10.4.1）**：一个「功能」通常应产生 **3-8 个提交**，不是一个。每个任务末尾给出的提交信息是**该提交**的信息，**不是该任务的唯一提交**；一个任务若含多个可独立回滚的部分（骨架 / 契约 / 领域实现 / 装配 / 测试 / 文档），就拆成多个提交。**但每一刀都必须保持可编译**——spec §10.4 末条：高频 ≠ 提交半成品。
- **证据制（spec §9 闸门 1）**：任何"已完成"结论必须附**可复现命令 + 真实输出**。禁止"应该可以了"。
- **依赖防幻觉（spec §9 闸门 3）**：新增依赖必须经 Maven Central 核实真实存在，并 `mvn dependency:tree` 解析成功。

## 本计划的文档书写约定

为避免与 spec 重复（违反单一事实源铁律），本计划对两类产物区别对待：

- **机制类产物**（配置、脚本、POM、CI、CLAUDE.md）——计划中给出**完整内容**，照抄即可。
- **提炼类产物**（charter / collaboration / STATUS / glossary / ADR）——计划中给出**精确的章节清单 + 来源映射**，执行时从对应章节提炼。这些将成为后续**活文档**，与冻结的 spec 分工是：**spec 记录"2026-09-13 我们定了什么"（历史快照，不再修改）；活文档是当前权威。**

> ⚠️ **架构相关的来源已变更，不要照 spec 抄**：`docs/superpowers/specs/2026-09-13-WingtiskyForum-design.md` 是本次架构变更**之前**冻结的快照，其 **§4.1（模块划分，旧 9 模块）、§8.1（目录结构，旧目录树）、§3（技术栈表，未写补丁版）、§6.2（M0 工期仍写 1 周）** 均**已过时**。
>
> **架构与模块相关的权威顺序是**：`docs/03-design/architecture.md`（§3 模块清单两态、§2 服务拆分、§6 资源预算）> `docs/02-decisions/ADR-0009/0010/0011` > 本计划 > spec。**提炼 charter / collaboration / glossary 时，凡涉及模块名、目录结构、工期、技术栈版本的，一律以 `architecture.md` 与本计划为准。** spec 仅在它未被本次变更触及的章节（§1 目标、§1.5 不做的事、§9 闸门、§10 Git 规范）才是权威。

---

## File Structure

本里程碑创建的文件及其职责：

```
WingtiskyForum/
├─ pom.xml                                    父聚合 POM：聚合入口 + 统一版本管理
├─ .gitattributes                             换行符规范（修 CRLF 警告）
├─ .gitmessage                                提交信息模板
├─ CLAUDE.md                                  ★ 项目宪法（自动加载入口）
├─ README.md                                  门面：简介 / 如何跑起来
├─ .github/workflows/ci.yml                   编译 + 单测 + 依赖与文档对账
├─ docs/
│  ├─ STATUS.md                               ★ 唯一状态源
│  ├─ 00-charter/charter.md                   章程（提炼自 spec §1、§6.1）
│  ├─ 00-charter/collaboration.md             协作协议（提炼自 spec §8、§10）
│  ├─ 01-requirements/glossary.md             术语表（防 AI 中途换词）
│  ├─ 02-decisions/README.md                  ADR 索引（11 条）
│  ├─ 02-decisions/ADR-0001-*.md ~ 0008-*     本计划补写的 8 条（0009~0011 已存在）
│  ├─ 04-log/01-M0-项目启动与协作基建.md       执行记录（spec §8.6，精炼）
│  ├─ 06-runbook/local-setup.md               ES8 / Kafka3.9 安装与启动
│  └─ 06-runbook/troubleshooting.md           踩坑记录
├─ tools/
│  ├─ check-deps.sh                           依赖真实性核查
│  └─ check-doc-refs.sh                       文档-代码一致性核查
├─ scripts/
│  ├─ start-es.bat                            中间件启停（Task 8 建，仅 ES 与 Kafka 两个）
│  └─ start-kafka.bat
├─ wt-common/pom.xml                          三个共享模块（叶子模块）
├─ wt-domain/pom.xml
├─ wt-infra/pom.xml
├─ forum/pom.xml                              内容域聚合（packaging=pom）
│  ├─ forum-user/pom.xml                         ├─ 四个内容域叶子模块
│  ├─ forum-content/pom.xml                      │
│  ├─ forum-search/pom.xml                       │
│  └─ forum-notify/pom.xml                       ┘
├─ trade/pom.xml                              交易域聚合（packaging=pom）
│  ├─ trade-product/pom.xml                      ├─ 三个交易域叶子模块
│  ├─ trade-order/pom.xml                        │
│  └─ trade-payment/pom.xml                      ┘
├─ seckill/pom.xml                            秒杀域聚合（packaging=pom）
│  └─ seckill-coupon/pom.xml                      └─ 一个秒杀域叶子模块
└─ app/pom.xml  +  WingtiskyForumApplication.java  +  src/test/.../ArchitectureTest.java
                                              ★ 唯一启动模块（唯一有 main()）
```

**叶子模块的文件构成**：每个叶子模块另有 `src/main/java/com/wingtisky/forum/<包路径>/package-info.java`（共 11 个；`app` 例外——它放启动类，不放 package-info）。包名映射见 Task 1 Step 5。

**M0 不建的东西**：`frontend/`（Vue3 独立工程，M2 建）、`forum-app` / `trade-app` / `seckill-app` / `gateway`（M9 才拆出来）。

**`scripts/` 只建两个**：Task 8 需要 `start-es.bat` / `start-kafka.bat`（本机无 Docker，中间件靠原生进程管理，启停参数必须固化）；**造数脚本不在此列**——造数属于 M3 起的业务开发，届时另建。

**职责边界**：`tools/` 只放**校验类**脚本（只读、不修改代码）；`scripts/` 留给造数与中间件启停。二者不混。

---

## Task 1: 三业务域 12 模块 Maven 骨架

**Files:**
- Create: `pom.xml`（父聚合 POM）
- Create: `wt-common/pom.xml`、`wt-domain/pom.xml`、`wt-infra/pom.xml`
- Create: `forum/pom.xml`、`trade/pom.xml`、`seckill/pom.xml`（三个域聚合 POM，`packaging=pom`）
- Create: `forum/forum-user/pom.xml`、`forum/forum-content/pom.xml`、`forum/forum-search/pom.xml`、`forum/forum-notify/pom.xml`
- Create: `trade/trade-product/pom.xml`、`trade/trade-order/pom.xml`、`trade/trade-payment/pom.xml`
- Create: `seckill/seckill-coupon/pom.xml`
- Create: `app/pom.xml`
- Create: `app/src/main/java/com/wingtisky/forum/WingtiskyForumApplication.java`
- Create: 11 个 `src/main/java/com/wingtisky/forum/<包路径>/package-info.java`（清单见 Step 5）

**Interfaces:**
- Consumes: 无（起点）
- Produces: 后续所有任务依赖的模块坐标 `com.wingtisky:wt-common:1.0.0-SNAPSHOT` 等 **12 个**（+ 3 个域聚合 POM）；启动类全限定名 `com.wingtisky.forum.WingtiskyForumApplication`；三个域的包前缀 `com.wingtisky.forum.forum..` / `com.wingtisky.forum.trade..` / `com.wingtisky.forum.seckill..`（Task 7 的 ArchUnit 规则直接引用，**改包名即失效**）

- [ ] **Step 0: 向用户确认**

**动手前必须先用下面这段话向用户说清三件事，并等用户回应才继续：**

① **这步做什么**：搭 Maven 骨架——三个共享模块（`wt-common`/`wt-domain`/`wt-infra`）+ 三个业务域（`forum/` 4 个、`trade/` 3 个、`seckill/` 1 个）+ 单进程启动模块 `app`，共 12 个叶子模块。**只有 POM 与 `package-info.java`，没有一行业务代码。**

② **为什么现在做**：后续每个里程碑都要往模块里填内容（M1 用户域、M2 内容域、M7 交易、M8 秒杀）；骨架不定，模块边界就只是文档里的一句话。而且本次模块结构已从 9 个重排为按三个服务划分（architecture.md §3.1 逻辑态）——**现在不按新边界搭，M9 物理拆分时就要重排目录、重写 POM、重跑 ArchUnit**。

③ **有哪几种做法、建议哪种、代价是什么**：
- **方案 A（建议）**：一个进程 + 12 模块 + 三层聚合（`wt-*` / 三个域 / `app`）。代价：本次要写 15 个 POM，`mvn` 输出更长；收益：域边界可被 ArchUnit 拦住，M9 拆分只搬目录、业务模块一行不改。
- 方案 B：不分域、12 个模块平铺。省一层聚合，但"哪些模块属于内容域"只能靠命名记忆，ArchUnit 的域规则要写四个并列包名而非一条 `..forum.forum..`。
- 方案 C：现在就拆三个进程（`forum-app`/`trade-app`/`seckill-app`）。违背 ADR-0010 的"先画逻辑边界，后做物理拆分"（拆分实验在 M9 才做），且开发期内存占用更高。

**建议 A，等用户回应后再进 Step 1。**

- [ ] **Step 1: 复核 Spring Boot 版本**

```bash
# 权威做法：读 maven-metadata.xml。不要用 solr 的 core=gav top-N 查询——
# 它按发布时间排序而非版本号，会把旧版本排在最前面。
curl -s --max-time 25 "https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml" \
  | grep -oE '<version>3\.5\.[0-9]+</version>' | tail -3
```
Expected: 输出 3.5.x 线的最高补丁版本号（2026-09-13 实测为 `3.5.16`）。**采用该版本。**

⚠️ **不要读 `<release>` / `<latest>` 字段**：它们可能指向 milestone 版本（实测该字段返回 `4.2.0-M1`），会把人误导到非稳定版。

- [ ] **Step 2: 提交① —— 父聚合 POM + 三个共享模块**

**本任务共拆 4 个提交，这是第 1 个。** 拆分规则：`<modules>` **逐刀追加**，每一刀后必须 `mvn -q clean compile` 通过；`dependencyManagement` 可以一次写全（它只是版本声明，不要求被声明的模块此刻已存在）。

创建 `pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.16</version>
    <relativePath/>
  </parent>

  <groupId>com.wingtisky</groupId>
  <artifactId>wingtisky-forum</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>wingtisky-forum</name>
  <description>技术社区 + 付费内容（对标 CSDN）—— Java 后端秋招简历项目</description>

  <!-- 逐刀追加：本提交只列三个共享模块 -->
  <modules>
    <module>wt-common</module>
    <module>wt-domain</module>
    <module>wt-infra</module>
  </modules>

  <properties>
    <java.version>17</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <!-- 版本统一在此管理，禁止在子模块散落版本号 -->
    <redisson.version>3.52.0</redisson.version>
    <caffeine.version>3.2.4</caffeine.version>
    <archunit.version>1.5.0</archunit.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- 内部模块：三个共享模块 -->
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>wt-common</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>wt-domain</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>wt-infra</artifactId>
        <version>${project.version}</version>
      </dependency>

      <!-- 内部模块：内容域 -->
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-user</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-content</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-search</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-notify</artifactId>
        <version>${project.version}</version>
      </dependency>

      <!-- 内部模块：交易域 -->
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>trade-product</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>trade-order</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>trade-payment</artifactId>
        <version>${project.version}</version>
      </dependency>

      <!-- 内部模块：秒杀域 -->
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>seckill-coupon</artifactId>
        <version>${project.version}</version>
      </dependency>

      <!-- 第三方（本里程碑仅声明管理，实际引入见各里程碑） -->
      <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson-spring-boot-starter</artifactId>
        <version>${redisson.version}</version>
      </dependency>
      <dependency>
        <groupId>com.github.ben-manes.caffeine</groupId>
        <artifactId>caffeine</artifactId>
        <version>${caffeine.version}</version>
      </dependency>

      <!-- 测试 -->
      <dependency>
        <groupId>com.tngtech.archunit</groupId>
        <artifactId>archunit-junit5</artifactId>
        <version>${archunit.version}</version>
        <scope>test</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

> **注意**：`dependencyManagement` 里**没有** `forum` / `trade` / `seckill` 三个聚合 POM 的坐标——聚合模块只用于组织构建，没有别的模块会依赖它；也**没有** `app`——它是叶子终点，不是被依赖方。

**三个共享模块的 POM**（`wt-common` 示例，`wt-domain`/`wt-infra` 替换 `<artifactId>`、`<description>` 与 `<dependencies>` 即可）：

```xml
  <parent>
    <groupId>com.wingtisky</groupId>
    <artifactId>wingtisky-forum</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>wt-common</artifactId>
  <name>wt-common</name>
  <description>通用：统一响应体、错误码、异常体系、工具、注解</description>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
  </dependencies>
```

（父 POM 与子模块 POM 的完整文件头照抄 Step 3 的模板；`<parent>` 与 `<artifactId>` 按上表替换。`wt-domain`/`wt-infra` 的依赖见 Step 4 的依赖表。）

**三个共享模块的 package-info**（`wt-common` 示例）：

```java
/**
 * 通用模块：统一响应体、错误码、异常体系、通用工具、注解。
 *
 * <p>边界约束：不依赖任何业务域（forum/**、trade/**、seckill/**），不含业务逻辑。
 */
package com.wingtisky.forum.common;
```

`wt-domain` / `wt-infra` 的包名为 `com.wingtisky.forum.domain` / `com.wingtisky.forum.infra`，职责一句话见 Step 5 的映射表。

Run: `mvn -q clean compile`
Expected: `BUILD SUCCESS`，无 ERROR。**若报 `javax.*` 相关错误说明父 POM 版本不对，回 Step 1。**

```bash
git add pom.xml wt-common wt-domain wt-infra
git commit -F - <<'EOF'
build(build): 建立父聚合 POM 与三个共享模块骨架

【为什么】
模块结构已按 architecture.md §3.1 的「逻辑态」由 9 模块重排为三域 12 模块。
骨架必须先立，后续里程碑才有地方填内容。这一刀只落最底下那层：
wt-common / wt-domain / wt-infra 是被三个业务域共同依赖的底座，
它们先成型，后面三刀才有可依赖的坐标。

【改了什么】
- 父 POM（wingtisky-forum，packaging=pom）：dependencyManagement 一次写全
  11 个内部模块坐标（三个共享模块 + 内容域 4 + 交易域 3 + 秒杀域 1）
  与 Redisson 3.52.0 / Caffeine 3.2.4 / ArchUnit 1.5.0（均经 Maven Central 核实）
- <modules> 本提交只列 wt-common / wt-domain / wt-infra（逐刀追加）
- 三个共享模块 POM：依赖严格按职责表（domain→common；infra→domain+common），
  未列入的依赖一律不加
- 三个 package-info.java 声明职责与边界

【验证】
- `mvn -q clean compile` → BUILD SUCCESS
- `ls wt-*/target/*.jar` → 三个 jar 均已生成（模块非空）

【关联】
Refs: ADR-0002（已修订）、ADR-0010、architecture.md §3.1
EOF
```

- [ ] **Step 3: 提交② —— 内容域 `forum/` 四个模块**

先核实 ArchUnit 最新稳定版，避免版本幻觉：

```bash
curl -s --max-time 25 "https://repo1.maven.org/maven2/com/tngtech/archunit/archunit-junit5/maven-metadata.xml" \
  | grep -oE '<version>[0-9]+\.[0-9]+\.[0-9]+</version>' | tail -3
```
Expected: 得到最新稳定版（2026-09-13 实测为 `1.5.0`）。与 Step 2 写入父 POM 的 `archunit.version` 对照，**不一致则改父 POM**。

**12 个叶子模块的 artifactId 与依赖如下表**（**依赖未列入的模块一律不加**；这是 Step 3/4/5 三次提交共同依据的权威表）：

| 模块 | artifactId | 依赖 |
|---|---|---|
| 共享·通用 | `wt-common` | `spring-boot-starter`（不引 web） |
| 共享·契约 | `wt-domain` | `wt-common` |
| 共享·基建 | `wt-infra` | `wt-domain`、`wt-common`、`spring-boot-starter` |
| 内容·用户 | `forum-user` | `wt-common`、`wt-domain`、`wt-infra`、`spring-boot-starter-web` |
| 内容·帖子 | `forum-content` | 同 `forum-user`（后台管理并入此模块，**不再单设 admin 模块**） |
| 内容·检索 | `forum-search` | `wt-common`、`wt-domain`、`wt-infra`、`spring-boot-starter` |
| 内容·通知 | `forum-notify` | 同 `forum-search` |
| 交易·商品 | `trade-product` | 同 `forum-user` |
| 交易·订单 | `trade-order` | 同 `forum-user` |
| 交易·支付 | `trade-payment` | 同 `forum-user` |
| 秒杀·券 | `seckill-coupon` | 同 `forum-search` |
| 启动 | `app` | 上表 11 个模块**全部** + `spring-boot-starter-web` + `spring-boot-starter-actuator` + `spring-boot-starter-test`(test) + `archunit-junit5`(test) |

> **为什么不引 web 的模块更多**：只有真正对完 HTTP 的模块（用户/帖子/商品/订单/支付/启动）才引 `spring-boot-starter-web`。检索与通知是**被调用方**（消费 Kafka 事件、提供 `wt-domain` 接口实现），秒杀扣减同理——引了 web 会让它们过早绑定 Tomcat，也让 ArchUnit 的"域边界"之外多出一条无意义的 web 依赖。

创建 `forum/pom.xml`（**域聚合 POM，`packaging=pom`，本身不写一行业务代码**）：

```xml
  <parent>
    <groupId>com.wingtisky</groupId>
    <artifactId>wingtisky-forum</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>forum</artifactId>
  <packaging>pom</packaging>
  <name>forum</name>
  <description>内容域聚合：论坛/评论/检索/通知（M9 拆为 forum-app）</description>

  <modules>
    <module>forum-user</module>
    <module>forum-content</module>
    <module>forum-search</module>
    <module>forum-notify</module>
  </modules>
```

四个叶子模块 POM 按统一模板（`forum-user` 示例，其余替换 `<artifactId>`、`<name>`、`<description>` 与 `<dependencies>`）：

```xml
  <parent>
    <groupId>com.wingtisky</groupId>
    <artifactId>forum</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>forum-user</artifactId>
  <name>forum-user</name>
  <description>用户：注册登录、认证、JWT、RBAC</description>

  <dependencies>
    <dependency>
      <groupId>com.wingtisky</groupId>
      <artifactId>wt-common</artifactId>
    </dependency>
    <dependency>
      <groupId>com.wingtisky</groupId>
      <artifactId>wt-domain</artifactId>
    </dependency>
    <dependency>
      <groupId>com.wingtisky</groupId>
      <artifactId>wt-infra</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
  </dependencies>
```

> **叶子模块的 `<parent>` 指向域聚合 POM，不是父聚合 POM。** 这不是风格选择——`forum-user` 的 parent 若直接写 `wingtisky-forum`，`forum/` 这一层聚合就白设了，且 `mvn -pl forum/forum-user` 的相对路径解析会不一致。版本号靠 `<parent>` 继承，叶子 POM 里仍**不出现任何 `<version>`**。

同时把 `forum` 追加进父 POM 的 `<modules>`：

```xml
  <modules>
    <module>wt-common</module>
    <module>wt-domain</module>
    <module>wt-infra</module>
    <module>forum</module>
  </modules>
```

各模块 `package-info.java` 的包名按 Step 5 的映射表；内容域的四个包为 `com.wingtisky.forum.forum.user` / `.forum.content` / `.forum.search` / `.forum.notify`。

> **注意包名里有两个 `forum`**：包根是 `com.wingtisky.forum`，域包段又按聚合目录名取 `forum`。这与权威结构"各模块包名按模块名映射"一致（`trade-order` → `com.wingtisky.forum.trade.order`，类比可得 `forum-user` → `com.wingtisky.forum.forum.user`），且让 ArchUnit 能用 `..forum.forum..` 一条规则圈住整个内容域。**这不是笔误**。

Run: `mvn -q clean compile`
Expected: `BUILD SUCCESS`。若报找不到 `wt-domain` 之类的坐标，说明父 POM 的 `<modules>` 少了 `forum`。

```bash
git add pom.xml forum
git commit -F - <<'EOF'
build(build): 新增内容域 forum/ 聚合模块与四个叶子模块骨架

【为什么】
内容域是四个业务域中最大的一块（用户/帖子/检索/通知），M1 起就要往
里面填代码。它先于交易域与秒杀域落地，因为 M1（用户域与安全基座）
是后续所有接口的前置——接口都要过 Spring Security。

【改了什么】
- forum/pom.xml：域聚合 POM（packaging=pom），只列四个子模块
- forum-user / forum-content / forum-search / forum-notify 四个叶子 POM，
  依赖严格按职责表：四个模块依赖 wt-*，仅 `forum-user` 与 `forum-content`
  引 spring-boot-starter-web
- 原计划的 forum-module-admin 不再单设——后台管理并入 forum-content
- 父 POM <modules> 追加 forum

【验证】
- `mvn -q clean compile` → BUILD SUCCESS
- `ls forum/*/target/*.jar` → 四个 jar（模块非空）

【关联】
Refs: architecture.md §3.1、spec §4.2
EOF
```


- [ ] **Step 4: 提交③ —— 交易域 `trade/` 三个模块与秒杀域 `seckill/` 一个模块**

创建 `trade/pom.xml` 与 `seckill/pom.xml`（域聚合 POM，照 `forum/pom.xml` 的模板替换 artifactId / name / description / modules）：

```xml
  <artifactId>trade</artifactId>
  <packaging>pom</packaging>
  <name>trade</name>
  <description>交易域聚合：商品/订单/支付（M9 拆为 trade-app）</description>

  <modules>
    <module>trade-product</module>
    <module>trade-order</module>
    <module>trade-payment</module>
  </modules>
```

```xml
  <artifactId>seckill</artifactId>
  <packaging>pom</packaging>
  <name>seckill</name>
  <description>秒杀域聚合：券库存与原子扣减（M9 拆为 seckill-app）</description>

  <modules>
    <module>seckill-coupon</module>
  </modules>
```

四个叶子模块 POM（`trade-product` 示例；`trade-order` / `trade-payment` 同、`seckill-coupon` 把 `spring-boot-starter-web` 换成 `spring-boot-starter`）：

```xml
  <parent>
    <groupId>com.wingtisky</groupId>
    <artifactId>trade</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>trade-product</artifactId>
  <name>trade-product</name>
  <description>商品：付费专栏/课程的上架与价格</description>

  <dependencies>
    <dependency>
      <groupId>com.wingtisky</groupId>
      <artifactId>wt-common</artifactId>
    </dependency>
    <dependency>
      <groupId>com.wingtisky</groupId>
      <artifactId>wt-domain</artifactId>
    </dependency>
    <dependency>
      <groupId>com.wingtisky</groupId>
      <artifactId>wt-infra</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
  </dependencies>
```

**四个模块的依赖一律照上表，不得出现"顺手加一个"。** 尤其注意 `trade-order` **不允许**依赖 `forum-user`——订单归属的用户信息通过 `wt-domain` 的契约接口拿（Step 6 会故意违反这条规则验证 ArchUnit 能抓住）。

包名：`com.wingtisky.forum.trade.product` / `.trade.order` / `.trade.payment` / `com.wingtisky.forum.seckill.coupon`。

同时把 `trade`、`seckill` 追加进父 POM 的 `<modules>`（累加到 Step 3 之后的列表）：

```xml
  <modules>
    <module>wt-common</module>
    <module>wt-domain</module>
    <module>wt-infra</module>
    <module>forum</module>
    <module>trade</module>
    <module>seckill</module>
  </modules>
```

Run: `mvn -q clean compile`
Expected: `BUILD SUCCESS`。

```bash
git add pom.xml trade seckill
git commit -F - <<'EOF'
build(build): 新增交易域与秒杀域模块骨架

【为什么】
交易域与秒杀域是本次业务域扩展新增的两块（ADR-0009/0010）。秒杀必须
独立成域而不是塞进交易——拆分理由是故障域隔离（秒杀瞬时脉冲会打满
线程池与连接池，与交易同进程时"秒杀被打挂 = 用户下不了单"），
这条理由不依赖流量大小，因此在本机环境依然成立。现在把边界划出来，
M9 物理拆分时只搬目录。

【改了什么】
- trade/pom.xml + trade-product / trade-order / trade-payment 三个叶子模块
- seckill/pom.xml + seckill-coupon 一个叶子模块
- 依赖严格按职责表：六个叶子模块均依赖 wt-*，仅交易域三个引
  spring-boot-starter-web（秒杀扣减是对外提供契约接口的被调用方，不引 web）
- 父 POM <modules> 追加 trade / seckill

【验证】
- `mvn -q clean compile` → BUILD SUCCESS
- `ls trade/*/target/*.jar seckill/*/target/*.jar` → 四个 jar

【关联】
Refs: ADR-0009、ADR-0010、architecture.md §2.2、§3.1
EOF
```

- [ ] **Step 5: 提交④ —— 启动模块 `app` 与 11 个 `package-info`**

`app/src/main/java/com/wingtisky/forum/WingtiskyForumApplication.java`：

```java
package com.wingtisky.forum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WingtiskyForumApplication {

    public static void main(String[] args) {
        SpringApplication.run(WingtiskyForumApplication.class, args);
    }
}
```

> **`app` 是 M0 阶段唯一的启动模块。** `WingtiskyForumApplication` 放在 `com.wingtisky.forum` 这个**最外层包**——Spring Boot 的组件扫描以启动类所在包为根，放这里才能扫到 `com.wingtisky.forum.*` 下的全部业务模块。**放到子包里会导致 M1 之后的 Bean 一个都扫不到**，且症状是"启动成功但接口 404"，很难查。`forum-app` / `trade-app` / `seckill-app` / `gateway` 在 M0 **不存在**（M9 才拆）。

`app/pom.xml` 的 `<dependencies>` 必须列全 11 个模块坐标（不靠传递依赖省事——`app` 是装配层，它显式声明依赖谁，就是"这个进程装了哪些域"的唯一可读清单）：

```xml
  <parent>
    <groupId>com.wingtisky</groupId>
    <artifactId>wingtisky-forum</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>app</artifactId>
  <name>app</name>
  <description>单进程启动模块：三个业务域逻辑态装配（M9 拆为三个 app + gateway）</description>

  <dependencies>
    <!-- 共享模块 -->
    <dependency><groupId>com.wingtisky</groupId><artifactId>wt-common</artifactId></dependency>
    <dependency><groupId>com.wingtisky</groupId><artifactId>wt-domain</artifactId></dependency>
    <dependency><groupId>com.wingtisky</groupId><artifactId>wt-infra</artifactId></dependency>
    <!-- 内容域 -->
    <dependency><groupId>com.wingtisky</groupId><artifactId>forum-user</artifactId></dependency>
    <dependency><groupId>com.wingtisky</groupId><artifactId>forum-content</artifactId></dependency>
    <dependency><groupId>com.wingtisky</groupId><artifactId>forum-search</artifactId></dependency>
    <dependency><groupId>com.wingtisky</groupId><artifactId>forum-notify</artifactId></dependency>
    <!-- 交易域 -->
    <dependency><groupId>com.wingtisky</groupId><artifactId>trade-product</artifactId></dependency>
    <dependency><groupId>com.wingtisky</groupId><artifactId>trade-order</artifactId></dependency>
    <dependency><groupId>com.wingtisky</groupId><artifactId>trade-payment</artifactId></dependency>
    <!-- 秒杀域 -->
    <dependency><groupId>com.wingtisky</groupId><artifactId>seckill-coupon</artifactId></dependency>
    <!-- 对外 -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <!-- 测试 -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.tngtech.archunit</groupId>
      <artifactId>archunit-junit5</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
```

`app` 额外需要 `spring-boot-maven-plugin` 的 `repackage` goal（其余模块**不要**加，否则会生成不可执行的 fat jar）：

```xml
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <executions>
          <execution>
            <goals><goal>repackage</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
```

**11 个 `package-info.java`** 的用途是**让空模块产生非空 jar** 并声明模块职责（`app` 不放 package-info——它放启动类）。以 `wt-common` 为例（路径 `wt-common/src/main/java/com/wingtisky/forum/common/package-info.java`）：

```java
/**
 * 通用模块：统一响应体、错误码、异常体系、通用工具、注解。
 *
 * <p>边界约束：不依赖任何业务域（forum/**、trade/**、seckill/**），不含业务逻辑。
 */
package com.wingtisky.forum.common;
```

**权威映射表**（模块 → 包名 → 一句话职责，逐条照抄，**不要临时改包名**——Task 7 的 ArchUnit 规则与 Task 6 的 `SRC_DIRS` 都依赖这张表）：

| 模块目录 | 包名 | package-info 中的职责描述 |
|---|---|---|
| `wt-common` | `com.wingtisky.forum.common` | 通用：统一响应体、错误码、异常体系、通用工具、注解 |
| `wt-domain` | `com.wingtisky.forum.domain` | 契约：领域模型 + 跨域接口（**只放契约，不放实现**） |
| `wt-infra` | `com.wingtisky.forum.infra` | 中间件基础设施：连接、序列化、Key 规范、重试（**机制，不含业务策略**） |
| `forum/forum-user` | `com.wingtisky.forum.forum.user` | 用户：注册登录、认证、JWT、RBAC |
| `forum/forum-content` | `com.wingtisky.forum.forum.content` | 内容：帖子、评论、标签、专栏、后台管理 |
| `forum/forum-search` | `com.wingtisky.forum.forum.search` | 检索：ES 索引、同步、检索 |
| `forum/forum-notify` | `com.wingtisky.forum.forum.notify` | 通知：站内信、事件通知 |
| `trade/trade-product` | `com.wingtisky.forum.trade.product` | 商品：付费专栏/课程的上架与价格 |
| `trade/trade-order` | `com.wingtisky.forum.trade.order` | 订单：下单、状态机、对账 |
| `trade/trade-payment` | `com.wingtisky.forum.trade.payment` | 支付：模拟支付、内容解锁凭证 |
| `seckill/seckill-coupon` | `com.wingtisky.forum.seckill.coupon` | 秒杀：券、库存、Lua 扣减、防刷 |

每个 `package-info.java` 除职责外，写一行**边界约束**，与 Task 7 的 ArchUnit 规则一一对应：

```java
/**
 * 用户：注册登录、认证、JWT、RBAC。
 *
 * <p>边界约束：属于内容域，不得依赖 trade/** 与 seckill/**；跨域数据通过 wt-domain 的契约拿。
 */
package com.wingtisky.forum.forum.user;
```

同时把 `app` 追加进父 POM 的 `<modules>`，**此时列表才完整**：

```xml
  <modules>
    <module>wt-common</module>
    <module>wt-domain</module>
    <module>wt-infra</module>
    <module>forum</module>
    <module>trade</module>
    <module>seckill</module>
    <module>app</module>
  </modules>
```

Run:
```bash
mvn -q clean compile
ls -d */target/classes */pom.xml | head -20
```
Expected: `BUILD SUCCESS`；`wt-common`、`wt-domain`、`wt-infra`、`forum/*`、`trade/*`、`seckill/*`、`app` 均有 `target/classes`。

```bash
git add pom.xml app
git commit -F - <<'EOF'
build(build): 新增 app 单进程启动模块与各模块 package-info

【为什么】
逻辑态要求"一个进程，但边界已按三个服务划好"——app 就是那个进程的
装配点，也是 M0 唯一的启动模块（M9 才拆成 forum-app/trade-app/seckill-app）。
启动类必须放在最外层包 com.wingtisky.forum，否则组件扫描扫不到各业务
模块，症状是"启动成功但接口全 404"。package-info 则解决另一个问题：
空模块产不出非空 jar，构建会报 warning 且模块边界只存在于文档里。

【改了什么】
- app 模块：WingtiskyForumApplication（唯一 main）+ repackage 插件
- app 的 POM 显式列全 11 个模块坐标——它是"这个进程装了哪些域"的唯一可读清单
- 11 个 package-info.java：声明职责 + 一行边界约束（与 ArchUnit 规则一一对应）
- 父 POM <modules> 补齐为 7 项（三共享 + 三域聚合 + app）

【验证】
- `mvn -q clean compile` → BUILD SUCCESS
- `ls -d */target/classes` → 各模块均有产物（模块非空）
- `cat app/target/*.jar` 对应的 fat jar 已生成

【关联】
Refs: architecture.md §3.1（逻辑态）、ADR-0010
EOF
```

- [ ] **Step 6: 验证编译与启动**

Run: `mvn -q clean compile`
Expected: `BUILD SUCCESS`，无 ERROR。**若报 `javax.*` 相关错误说明父 POM 版本不对，回 Step 1。**

Run（后台启动，25 秒后探测健康端点，然后关闭）：
```bash
mvn -q -pl app -am spring-boot:run &
sleep 25
curl -s http://localhost:8080/actuator/health
kill %1
```
Expected: 输出 `{"status":"UP"}`。日志中可见 `Started WingtiskyForumApplication`。

- [ ] **Step 7: 收尾自查（提交前逐条对照 `git diff`）**

```bash
git log --oneline -4
grep -c '<module>' pom.xml
ls -d wt-* forum forum/* trade trade/* seckill seckill/* app
```

Expected:
- 最近 4 个提交即本任务的 4 刀（**不是 1 个**）；
- `pom.xml` 中有 **7** 个 `<module>`（`wt-common`、`wt-domain`、`wt-infra`、`forum`、`trade`、`seckill`、`app`）——注意**不是 12**，叶子模块列在三个域聚合 POM 里；
- 目录清单包含 12 个叶子模块 + 3 个域聚合目录，**且没有 `forum-module-*`、`forum-boot`、`forum-app`、`trade-app`、`seckill-app`、`gateway`、`frontend`**；**此刻也应当没有 `scripts/`**——它由 Task 8 才创建（`tools/` 在 Task 6 已建，两者不同）。

**逐条对照 `git diff`**（spec §9 闸门 4）：确认每个提交的 `【改了什么】` 都能在 `git show` 里找到对应改动，没有"声称了却没做"的条目。

---

## Task 2: 提交规范落地

**Files:**
- Create: `.gitattributes`
- Create: `.gitmessage`
- Modify: 本地 git 配置（`commit.template`、`core.hooksPath` 暂不设）

**Interfaces:**
- Consumes: Task 1 的仓库
- Produces: 每次 `git commit` 自动带出模板；`.gitattributes` 消除 CRLF 警告

- [ ] **Step 0: 向用户确认**

① **这步做什么**：落地提交规范的两个机械保障——`.gitattributes`（统一换行符）+ `.gitmessage`（提交模板，含【为什么/改了什么/验证/关联】四节）+ 本地 `commit.template` 配置。

② **为什么现在做**：现在本地 `git add` 就在报 `LF will be replaced by CRLF`，不修则后续每个提交都可能出现"整文件变更"的假 diff，把真实改动淹没。而 Task 1 刚完成 4 次提交——**规范要赶在后续所有提交之前生效**，否则前 4 个提交的格式要事后补。

③ **做法与建议**：
- **方案 A（建议）**：`.gitattributes` 用 `* text=auto`，仓库内 LF、Windows 工作区 CRLF。代价：首次应用后需要一次 `git add --renormalize .` 才彻底生效。
- 方案 B：全部强制 `eol=lf`（含 `.bat`）。会让 Windows 原生的 `.bat` 脚本在某些编辑器下显示异常行尾，得不偿失。
- 方案 C：只配 `.gitmessage`，不做 `.gitattributes`。留下 CRLF 假 diff 问题，不推荐。

**建议 A，等用户回应后再进 Step 1。**

- [ ] **Step 1: 写 `.gitattributes`**

```
# 统一换行符：仓库内一律 LF，Windows 工作区检出为 CRLF
* text=auto
*.java   text diff=java
*.xml    text diff=xml
*.md     text
*.yml    text
*.yaml   text
*.sh     text eol=lf
*.bat    text eol=crlf
*.png    binary
*.jpg    binary
*.pdf    binary
```

- [ ] **Step 2: 写 `.gitmessage`**

```
<type>(<scope>): <subject，≤50字符，祈使句，句末不加句号>

【为什么】
<问题是什么、为什么要改。必须能脱离上下文独立看懂。
写不出"为什么"，说明这个提交不该存在。>

【改了什么】
- <人的意图摘要，不是 diff 的复述>

【验证】
- `<命令>` → <真实结果>

【关联】
Refs: ADR-xxxx
# ---------- 以下为填写提示，提交前请删除 ----------
# type  : feat|fix|docs|refactor|perf|test|chore|build|ci|revert
# scope : 业务改动用 m<里程碑号>-<域>（如 m3-cache）；基建用 infra|build|ci|docs
# 禁止  : "fix bug" / "update" / "优化一下" / 空 body / 主体与 diff 不符
```

- [ ] **Step 3: 启用模板并验证**

```bash
git config commit.template .gitmessage
git commit --allow-empty   # 不开编辑器会失败，这是预期
```
更好的验证方式：`git config --get commit.template` 应输出 `.gitmessage`；然后随便 `git commit` 一次观察编辑器是否带出模板（退出不保存即可）。

- [ ] **Step 4: 验证 CRLF 警告消失**

```bash
git add .gitattributes .gitmessage
git status   # 不应再出现 "LF will be replaced by CRLF" 警告
```
Expected: 无该警告。

- [ ] **Step 5: 提交**

**本任务不拆提交**：`.gitattributes`（换行符）与 `.gitmessage`（模板）是同一个机制的两半——"让提交看起来是它该有的样子"，回滚时机完全一致（一个回滚了另一个就没意义）。按 spec §10.4.1 的拆分信号逐条比对，二者既不构成"两件不相关的事"，也不落在"契约/领域/装配/测试"的不同层，因此不满足拆分条件。

```bash
git commit -F - <<'EOF'
chore(build): 落地提交信息模板与换行符规范

【为什么】
此前 git add 持续报 "LF will be replaced by CRLF"，长期下去会造成
"整文件变更"的假 diff，掩盖真实改动。同时提交信息缺少强制结构，
无法支撑"靠 commit 历史复盘每一步迭代"的目标（spec §10.3）。

【改了什么】
- .gitattributes：仓库内统一 LF，脚本强制 LF、批处理强制 CRLF，
  二进制文件标记 binary
- .gitmessage：提交模板，含【为什么/改了什么/验证/关联】四节与填写提示
- git config commit.template 指向该模板

【验证】
- `git config --get commit.template` → .gitmessage
- `git add .gitattributes .gitmessage` → 不再出现 CRLF 警告

【关联】
Refs: spec §10.3、§10.4
EOF
```

---

## Task 3: 项目宪法 CLAUDE.md

**Files:**
- Create: `CLAUDE.md`

**Interfaces:**
- Consumes: Task 1 的模块结构与版本号
- Produces: 每次新对话自动加载的入口文件；其他文档引用其中的"技术栈白名单"

- [ ] **Step 0: 向用户确认**

① **这步做什么**：写 `CLAUDE.md`——每次新对话自动加载的项目宪法，装技术栈白名单（含禁止项）、模块边界、提交规范、会话协议、防幻觉六道闸门、环境事实。

② **为什么现在做**：Task 1 已经把新模块结构落成事实，CLAUDE.md 若不更新，**下一个对话会照着旧的 9 模块结构干活**（它比 spec 优先被读到，因为自动加载）。这正是 spec §8.4 说的漂移：约定没有单一入口，每次对话都要重新解释。而且它是 Task 4（charter/collaboration）与 Task 7（ArchUnit）共同引用的上游。

③ **做法与建议**：
- **方案 A（建议）**：一个文件装全部硬约束，篇幅约 120 行。代价：文件偏长，但"一次读完就够"比"读五个文件"更适合新对话冷启动。
- 方案 B：拆成 `CLAUDE.md`（薄入口）+ `docs/00-charter/rules.md`（细则）。代价：多一跳，而新对话最容易漏读的恰恰是第二跳。
- 方案 C：不写 CLAUDE.md，靠 spec。spec 已冻结为历史快照且不含新模块结构，直接与 Task 1 的事实冲突。

**建议 A，等用户回应后再进 Step 1。**

- [ ] **Step 1: 写 `CLAUDE.md`**

内容如下（照抄，仅当模块名或版本变化时同步修改）：

````markdown
# Wingtisky Forum — 项目宪法

> 本文件被 Claude Code **每次对话自动加载**。它是接手的第一个入口。
> 完整设计见 `docs/superpowers/specs/2026-09-13-WingtiskyForum-design.md`（历史快照，已冻结）。
> 当前状态见 **`docs/STATUS.md`**（唯一状态源）。

## 项目是什么

**技术社区 + 付费内容**（对标 CSDN），Java 后端秋招简历项目。仓库：
https://github.com/Wingtisky20/wingtisky-forum

业务闭环：**内容 → 付费内容 → 交易 → 解锁 → 回到内容**。三个服务：`forum`（内容）/ `trade`（交易）/ `seckill`（秒杀）。

**不是**旧项目（`D:\aaaDocuments\aaaCommunity\community`）的复刻。旧项目**只读参考，禁止修改**。

## 技术栈白名单

**任何不在下表的技术/依赖，必须先问用户，不得擅自引入。**

| 层 | 选型 | 版本 |
|---|---|---|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.5.16 |
| 安全 | Spring Security | 由 Boot 管理（6.x） |
| 持久层 | MyBatis（待 M2 定，见 §12 开放问题） | — |
| 数据库 | MySQL | 8.0 |
| 缓存 | Redis + Redisson + Caffeine | Redis 5.0.14.1 / Redisson 3.52.0 / Caffeine 3.2.4 |
| 消息 | Kafka（KRaft） | 3.9.x |
| 检索 | Elasticsearch + IK | 8.x |
| 前端 | Vue3 + Vite + Element Plus | — |
| 构建 | Maven 多模块（三域 12 模块） | — |

**禁止**：Docker · 分库分表 · Kafka Streams/Flink · 任何未列入的框架

**M0–M8 不做微服务配套**（Nacos / Gateway / Sentinel / Seata 均不引入，理由见 architecture.md §4.2）；M9 才拆进程，且只引入 Nacos + Gateway——**面试话术要点是"我评估过 Sentinel 与 Seata 但没用"，不是"我都用上了"**。

## 模块边界（强制，按三个服务划分）

```
wt-common      通用：响应体/错误码/异常/工具/注解 —— 不依赖任何业务域
wt-domain      领域模型 + 跨域接口契约（只放契约，不放实现）
wt-infra       中间件基础设施（机制，不含业务策略）

forum/        内容域：forum-user / forum-content / forum-search / forum-notify
trade/        交易域：trade-product / trade-order / trade-payment
seckill/      秒杀域：seckill-coupon

app/          单进程启动模块（唯一有 main()）—— M0–M8 的唯一启动点
```

**域间边界是本次架构的核心**：`forum/**`、`trade/**`、`seckill/**` **三者两两不得互相依赖**（这条不是"尽量"，是 ArchUnit 会拦的红线）；跨域数据一律走 `wt-domain` 的契约接口。

**边界线画在「机制 vs 策略」上**：连接、序列化、Key 规范属 `wt-infra`；缓存存多久、哪些事件幂等、索引怎么映射属业务模块。
边界由 Maven 依赖约束 + ArchUnit 测试强制（`app/src/test/java/com/wingtisky/forum/ArchitectureTest.java`）。

**M9 才做的事**：拆出 `forum-app` / `trade-app` / `seckill-app` / `gateway`。**M0–M8 不建这些模块**，业务模块本身届时一行不改。

## 提交规范（强制）

格式见 `.gitmessage`。**四节：`【为什么】【改了什么】【验证】【关联】`**
- `【为什么】` 必填——写不出为什么，说明这个提交不该存在
- `【验证】` 必填——附**命令 + 真实结果**，不接受"应该没问题"
- scope：业务用 `m<里程碑>-<域>`（如 `m3-cache`）；基建用 `infra`/`build`/`ci`/`docs`
- **禁止**：`fix bug` / `update` / `优化一下` / 空 body / 主体与 diff 不符
- **禁止直接 push main**；走 feature 分支 + PR + Squash merge

**粒度（spec §10.4.1）**：一个「功能」通常应产生 **3–8 个提交**，不是一个。可拆的维度：契约先行 / 领域与装配分离 / 测试独立 / **修复独立**（bug 修复永不混进功能提交）/ 文档独立。
**高频不等于半成品**——进入 `main` 的每一个提交都必须可编译。

## 用户参与（强制，优先级高于任何执行技能）

**判据：面试可讲性。** 凡面试官可能追问"你为什么这么做"的决策，用户必须参与。

- **任务前置确认**：动手前用人话说清三件事，**等用户回应才动手**：① 做什么 ② 为什么现在做 ③ 有哪几种做法/我建议哪种/代价
- **决策不替用户拍板**：写明「我建议 X，因为 Y，你不反对我就按 X 做」；**用户未表态时不得静默执行**
- **设计先于代码**：模块划分/库表/接口/缓存策略先出文档给用户审，再写代码
- **暂停权**：用户说"停，这里我先看看"即停
- ⚠️ `subagent-driven-development` 等技能默认"任务之间不停下确认"——**该默认在本项目失效**

## 会话协议

**开场**——用户说「读文档，继续」时，按序读 3 个文件：
1. 本文件（自动加载）
2. `docs/STATUS.md` —— 唯一入口，其中指明"当前上下文文件"
3. `docs/00-charter/collaboration.md`

然后**必须先向用户复述**：当前阶段 → 上次做到哪 → 本次要做什么 → 有哪些阻塞。
**用户确认后才动手。**

**收场**——五件事缺一不可：
1. 更新 `docs/STATUS.md`（进度 + **证据等级** + 下一步 + 阻塞）
2. 写 `docs/04-log/` 下的执行记录（**按里程碑分文件，精炼，宁少勿多**，见 spec §8.6）
3. 有新决策 → 写 ADR
4. `git commit`（严格格式）
5. **向用户报告**：真实改了什么、验证到什么程度、遗留什么

## 三条防矛盾铁律

1. **单一事实源**：状态只在 `STATUS.md`；需求只在 PRD；决策只在 ADR。别处**只引用、不复制**。
2. **变更留痕**：推翻旧结论 → **必须新增 ADR** + 在 STATUS.md 标注。禁止静默修改。
3. **里程碑闸门**：每个里程碑结束打 tag（`m1-done`），可回滚、可对照。

## 防幻觉六道闸门

1. **证据制** —— 结论必附命令+真实输出；`STATUS.md` 标注 `[REAL]`/`[SYNTHETIC]`/`[UNVERIFIED]`/`[BLOCKED]`
2. **反验证剧场** —— 端到端必须真实数据；亮点实测必须有**反面对照**
3. **依赖防幻觉** —— 新依赖必须 Maven Central 真实存在 + `mvn dependency:tree` 可解析；**禁止**"我记得有个库叫…"
4. **提交与 diff 对账** —— 提交前逐条对照 `git diff`
5. **高危区点名** —— Spring Security 6 / Boot 3 的 `jakarta.*` / ES 8 Java API Client / Kafka KRaft / Redisson：**先读官方文档或本地 jar 源码，再写代码**
6. **文档-代码对账** —— `tools/check-doc-refs.sh` 与 `tools/check-deps.sh` 进 CI

## 环境事实（2026-09-13 实测）

- 内存 16G · CPU Ryzen 5 4500U（6 核）· **C 盘仅剩 ~19G，D 盘 ~229G——安装一律放 D 盘**
- JDK 17.0.16 已装 · Maven / Node 在 `D:\aaaSoftware`
- 中间件在 `D:\aaaSoftware`：MySQL 8.0.41（运行中）、Redis 5.0.14.1、Kafka 3.9(KRaft, M0 新装)、ES 8.x(M0 新装)
- **无 Docker、无 WSL2** —— 集成测试连本地中间件，不在 CI 跑（ADR-0007）

## 上下文卫生

- **一个对话一件事**。一个功能做完就收场，不在同一对话连做三个。
- 对话变长时**主动建议用户开新对话**，而非硬撑到输出质量下降。
- 永远不要问「你怎么实现的」，而要告诉下游「我们的约定是什么」。
````

- [ ] **Step 2: 验证模块名与版本号已同步到新架构**

Run:
```bash
ls -la CLAUDE.md
grep -cE 'forum-module-|forum-boot|forum-common|forum-domain|forum-infra|04-journal' CLAUDE.md
grep -nE '3\.5\.16|Redisson 3\.52\.0|Caffeine 3\.2\.4' CLAUDE.md
```
Expected:
- 文件位于 `D:\aaaDocuments\project\WingtiskyForum\CLAUDE.md`；
- 第二条命令输出 **0**——CLAUDE.md 中**不得残留任何旧模块名，也不得出现 `04-journal`**；
- 第三条命中版本行（3.5.16 / Redisson 3.52.0 / Caffeine 3.2.4），与 Task 1 的父 POM 一致。

（真正的"自动加载"验证需在新对话中确认——**这是 M0 收尾时要做的一次实测**，记入 Task 10。）

**本任务不拆提交**：CLAUDE.md 是一份文件、一个逻辑变更，拆开会出现"半个宪法"的中间态（例如边界改了一半，新对话读到的是自相矛盾的约定）。

- [ ] **Step 3: 提交**

```bash
git add CLAUDE.md
git commit -F - <<'EOF'
docs(charter): 重写项目宪法 CLAUDE.md 以匹配三域架构

【为什么】
架构大改后 CLAUDE.md 里的模块清单（9 个 forum-module-*）已与代码事实
不符，而它每次对话自动加载、比 spec 更早被读到——不改就会导致下一个
对话照着旧结构干活。"新对话能否顺利接手"是硬要求，所以这份文件必须
在 M0 期间同步到位，而不是等谁发现了再补。

【改了什么】
- 产品定位：技术社区 → 技术社区 + 付费内容（CSDN 形态），补业务闭环
- 技术栈白名单版本对齐父 POM 实测值：Boot 3.5.16 / Redisson 3.52.0 /
  Caffeine 3.2.4
- 禁止项改写：删掉"微服务/注册中心/网关"（M9 要引入 Nacos+Gateway，
  原文自相矛盾），改为"M0–M8 不做微服务配套"，并补上 Sentinel/Seata
  评估过但不用的话术
- 模块边界重写为三域 12 模块，点明 forum/**、trade/**、seckill/** 两两
  互斥是核心红线，以及 M9 才建 forum-app/trade-app/seckill-app/gateway
- 新增「用户参与（强制）」小节：任务前置确认三问 + 决策不替用户拍板 +
  design-first + 暂停权 + 技能默认值失效
- 提交规范补「粒度 3–8 个提交」与「修复独立成条」
- 收场流程第 2 条：04-journal/按日期 → 04-log/按里程碑（spec §8.6）

【验证】
- `grep -cE 'forum-module-|forum-boot|04-journal' CLAUDE.md` → 0
- `grep -nE '3\.5\.16|Redisson 3\.52\.0' CLAUDE.md` → 与父 POM 一致
- 实际自动加载效果在 Task 10 的新对话中实测

【关联】
Refs: ADR-0002（已修订）、ADR-0005（已废弃）、ADR-0009/0010、spec §8.5、§8.6、§10.4.1、architecture.md §3.1
EOF
```

---

## Task 4: 协作文档（charter / collaboration / STATUS / glossary）

**Files:**
- Create: `docs/00-charter/charter.md`
- Create: `docs/00-charter/collaboration.md`
- Create: `docs/STATUS.md`
- Create: `docs/01-requirements/glossary.md`

**Interfaces:**
- Consumes: spec 的对应章节、Task 3 的 CLAUDE.md
- Produces: `docs/STATUS.md` —— **后续所有任务与会话的状态写入点**，其"当前上下文文件"字段被会话协议读取

- [ ] **Step 0: 向用户确认**

① **这步做什么**：写四份"活文档"——`charter.md`（章程：固定范围）、`collaboration.md`（协作协议：固定流程与接手指令）、`docs/STATUS.md`（唯一状态源）、`glossary.md`（术语表）。**都是提炼自 spec 的结论，不写设计论证过程。**

② **为什么现在做**：Task 1–3 已把结构、规范、宪法落好，但**"进度"还没有单一存放点**——后续每个任务、每次会话都要读写它。而章程若不在此刻钉死"明确不做的事"，M1 一开始讨论功能就会开始蔓延。同时 Task 10 的收尾要更新 STATUS.md，现在不建它，收尾时无表可填。

③ **做法与建议**：
- **方案 A（建议）**：四份文档一次建好，STATUS.md 直接建到含完整里程碑表（M0–M11）与证据等级图例。代价：本次要写四份文件，但它们是**只此一次**的基建，后续只增改不重写。
- 方案 B：先建 STATUS.md，章程与协作协议等 M1 再补。代价：M1 开始就在无章程状态下讨论需求，且会话协议缺 collaboration.md 这一环，"读文档继续"接不上。
- 方案 C：把四份合并成一份 `PROJECT.md`。违反 spec §8.1 的目录约定，且 STATUS.md 会被写得频繁改动的进度条淹没。

**建议 A，等用户回应后再进 Step 1。**

- [ ] **Step 1: 从 spec 提炼 `charter.md`**

来源：spec §1.1–1.5、§6.1。**这是提炼不是复制**——去掉设计论证过程，只留结论。章节必须为：

```
# 项目章程
## 1. 一句话定义
## 2. 背景与目标（spec §1.1、§1.2）
## 3. 五个必须落地的亮点（spec §1.3，保留表格）
## 4. 成功标准（spec §1.4，保留 checklist）
## 5. 产品定位（spec §6.1）
## 6. 明确不做的事（spec §1.5，逐条保留——这是抵抗范围蔓延的依据）
## 7. 权威文档索引（指向 spec / STATUS.md / ADR 索引 / 本文件的分工）
```

**关键点**：§6 必须逐条保留，因为它会在后续每次"要不要加个功能"的讨论中被引用。

- [ ] **Step 2: 从 spec 提炼 `collaboration.md`**

来源：spec §8、§10、§9。章节必须为：

```
# 协作协议
## 1. 会话开场流程（3 个文件 + 复述确认）
## 2. 会话收场流程（五件事）
## 3. 三条防矛盾铁律
## 4. 上下文卫生
## 5. 目录结构说明（spec §8.1）
## 6. 防幻觉六道闸门简述（详表指针 → spec §9）
## 7. Git 规范速查（分支模型 / 提交格式 / 提交粒度 / 回滚 / tag）
## 8. 里程碑工作流（feature 分支 → PR → Squash merge → tag）
## 9. 敏感信息处理（环境变量 / 误提交三步应急）
## 10. 用户参与机制（spec §8.5：任务前置确认三问 / 变更简报 / 设计先于代码 /
##     决策不替用户拍板 / 暂停权；并写明"执行技能的默认值在本项目失效"）
## 11. 执行记录约定（spec §8.6：`docs/04-log/` 按里程碑分文件、精炼优先、
##     上接指针；与 commit 的分工）
```

**§10 与 §11 是本次新增**，不是可选章节：spec §8.5 明确「优先级高于任何执行技能」，spec §8.6 用 `04-log/` 取代了旧的 `04-journal/`。协作协议若不含这两节，"读文档继续"接手的对话就不知道有确认闸门这回事。

**目录结构说明（§5）要按新结构写**：`wt-common`/`wt-domain`/`wt-infra` + `forum/` / `trade/` / `seckill/` 三域聚合 + `app`，并注明 `frontend/`（M2 建）与 `scripts/`（本阶段仅 ES/Kafka 两个启停脚本，造数脚本 M3 起）的情况。**不要从 spec §8.1 照抄旧的 `forum-module-*` 清单**——spec 的目录树是 2026-09-13 的冻结快照，架构变更后以本计划与 `architecture.md` §3.1 为准。

- [ ] **Step 3: 写 `docs/STATUS.md`（唯一状态源）**

**这份文件的格式至关重要**——后续每次会话都读写它。必须严格为：

```markdown
# 项目状态

> **唯一状态源。** 进度只写在这里，别处只引用不复制。
> 最后更新：2026-09-13

## 当前阶段

**M0 · 项目启动与协作基建**（进行中）

## 当前上下文文件

> 新对话接手时，除 `CLAUDE.md` 和本文件外，还需读这几个：

- `docs/03-design/architecture.md`（§2 服务拆分、§3 模块清单两态、§6 资源预算）
- `docs/00-charter/collaboration.md`（会话协议 + 用户参与机制 + 执行记录约定）

## 里程碑进度

| 里程碑 | 状态 | Tag | 备注 |
|---|---|---|---|
| M0 启动与基建 | 进行中 | — | 见下方任务清单 |
| M1 用户域与安全基座 | 未开始 | — | 亮点 5 |
| M2 内容域核心 | 未开始 | — | 前端 Vue3 工程同建 |
| M3 亮点1 多级缓存 | 未开始 | — | |
| M4 亮点2 Kafka 异步写 | 未开始 | — | |
| M5 亮点3 ES 检索 | 未开始 | — | |
| M6 亮点4 深翻页 | 未开始 | — | |
| M7 交易域 | 未开始 | — | 商品/订单/支付/解锁 |
| M8 秒杀 | 未开始 | — | 亮点 6/7/8 |
| M9 服务化改造 | 未开始 | — | 物理拆分 + A/B 对照实验 |
| M10 压测与交付 | 未开始 | — | 一期收口 |
| M11 AI 演进 | 未开始 | — | 二期 |

> **工期基线（spec §6.2，2026-09-13）**：M0 = 1.5 周；一期 M0–M10 ≈ 32 周。**M1 结束时必须用实测速度重新校准后续所有里程碑**，并把校准结果写回本表。

## M0 任务清单

| # | 任务 | 状态 | 证据等级 |
|---|---|---|---|
| 1 | 三业务域 12 模块 Maven 骨架 | 未开始 | — |
| 2 | 提交规范落地 | 未开始 | — |
| 3 | 项目宪法 CLAUDE.md | 未开始 | — |
| 4 | 协作文档 | 未开始 | — |
| 5 | ADR 机制与首批 8 条 ADR | 未开始 | — |
| 6 | 防漂移脚本 | 未开始 | — |
| 7 | 架构边界测试 + CI 骨架 | 未开始 | — |
| 8 | 本地中间件 ES8 + Kafka3.9 | 未开始 | — |
| 9 | GitHub 仓库创建与推送 | 未开始 | — |
| 10 | M0 收尾 | 未开始 | — |

## 证据等级图例

| 标记 | 含义 |
|---|---|
| `[REAL]` | 真实链路跑过，附命令与输出 |
| `[SYNTHETIC]` | 只用造的数据或单测跑过，**未经真实链路** |
| `[UNVERIFIED]` | 只是写完了，没验证 |
| `[BLOCKED]` | 卡住，附原因 |

## 阻塞项

（无）

## 下一步

执行 M0 Task 2：落地提交规范（`.gitattributes` + `.gitmessage` + 本地模板配置）。

## 待核实

| 事项 | 何时必须核实 |
|---|---|
| Redisson 3.52.0 与 Redis 5.0.14.1 的兼容性（是否有 Redis 5 不支持的命令） | M3 首次接入时；不兼容则降级并记入 ADR-0001 修订 |
| M0 工期 1.5 周是预估值，未按实际速度校准 | M0 结束时 |
| ES 8.18.3 与 Kafka 3.9.1 的下载路径可达性 | Task 8 执行时（不得凭记忆写死 URL） |
```

- [ ] **Step 4: 写 `docs/01-requirements/glossary.md` 术语表**

来源：spec §6.1（业务术语）、§5（亮点术语）、`architecture.md` §1–§3（域与服务名）。用途是**防止 AI 中途换词**（一会儿"帖子"一会儿"文章"，一会儿"付费专栏"一会儿"课程"），这也是 spec §9 闸门 5 之外的隐性漂移源。

必须包含的术语条目（每条一行：术语 | 英文/代码标识 | 定义 | 反例「不要叫」）：

**业务术语（内容域）**

| 术语 | 代码标识 | 定义 | 不要叫 |
|---|---|---|---|
| 帖子 | `Post` | 社区的主要内容单元，长文技术分享 | 文章、话题 |
| 评论 | `Comment` | 对帖子的回复，支持二级回复 | 回复、留言 |
| 标签 | `Tag` | 帖子的分类标记 | 分类、话题 |
| 专栏 | `Column` | 帖子的集合，作者自建；**可设为付费** | 专题、合集 |
| 付费专栏 | `PaidColumn` | 需购买才能阅读全文的专栏 | VIP 专栏、收费文章 |
| 用户 | `User` | 注册账号 | 会员、账户 |
| 版主 | `Moderator` | 角色之一，可管理内容 | 管理员（那是 `Admin`） |

**业务术语（交易域与秒杀域）**

| 术语 | 代码标识 | 定义 | 不要叫 |
|---|---|---|---|
| 商品 | `Product` | 付费专栏/课程的上架形态，有价格 | 货物、SKU |
| 订单 | `Order` | 一次购买行为的记录与状态机 | 单据、交易记录 |
| 支付 | `Payment` | 模拟支付（无真实资金链路） | 结算、收款 |
| 解锁凭证 | `UnlockGrant` | 支付成功后发放的内容访问权证明 | 授权码、许可 |
| 优惠券 | `Coupon` | 运营配置的折扣凭证，经秒杀发放 | 券码、卡券 |
| 秒杀 | seckill | 瞬时脉冲式的限量抢券 | 抢购、闪购 |
| 扣减 | deduct | 在 Redis 中用 Lua **原子**扣券库存 | 减少、更新库存 |
| 回补 | rollback / restore | 订单超时未付时把库存加回 | 恢复、返还 |
| 超卖 | oversell | 库存被扣成负数；本项目闸门是「超卖 0」 | 多发、重复发券 |

**技术术语（缓存与一致性，面试必问）**

| 术语 | 代码标识 | 定义 | 不要叫 |
|---|---|---|---|
| 命中 | cache hit | L1/L2 缓存命中 | 缓存成功 |
| 回源 | fallback to DB | 缓存未命中后查 MySQL | 穿透（那是 cache penetration，是另一回事） |
| 击穿 | cache stampede | 热点 key 失效瞬间大量并发回源 | 穿透、雪崩 |
| 穿透 | cache penetration | 查询不存在的数据，每次都绕过缓存打到 DB | 击穿 |
| 雪崩 | cache avalanche | 大量 key 同时失效 | 击穿、穿透 |
| 幂等 | idempotent | 同一操作重复执行只产生一次效果（靠 `eventId` + `SETNX`） | 去重、防重 |
| 本地消息表 | outbox | 业务写库与"消息待投递"同事务落库，再发 MQ，保证消息不会丢 | 事务消息（那是 RocketMQ 的说法） |
| 对账 | reconcile | 定时比对两处数据的差异并修正（Redis 库存 vs DB 订单数） | 校验、比对 |

**服务与模块名（禁止换词）**

| 术语 | 代码标识 | 定义 | 不要叫 |
|---|---|---|---|
| 内容域 | `forum/**` | 用户/帖子/检索/通知；M9 的物理形态是 `forum-service` | 社区域、论坛模块 |
| 交易域 | `trade/**` | 商品/订单/支付 | 商城域、订单域 |
| 秒杀域 | `seckill/**` | 券与库存扣减 | 促销域 |
| 逻辑态 | — | M0–M8：一个进程，模块边界按三服务划好 | 单体（易与"没边界"混淆） |
| 物理态 | — | M9 起：三个独立启动模块 + 网关 | 微服务（本项目不做全套微服务） |

**注意**：① 击穿/穿透/雪崩三者极易混用，且面试必问——术语表把它们钉死；② 幂等/超卖/对账是 M4/M8 的闸门关键词，写错会导致验收标准对不上；③ 模块名一旦在术语表钉死，Task 6 的 `SRC_DIRS` 与 Task 7 的 ArchUnit 包名**必须逐字一致**。

- [ ] **Step 5: 提交**

**本任务拆 3 个提交**，按"面向对象不同"拆：STATUS.md 是**机器与会话读的**（会话协议的第一跳），charter + collaboration 是**流程规范**，glossary 是**术语约定**。三者回滚时机不同——术语表改错不必动状态源。

```bash
# 提交①：状态源（会话协议的入口）
git add docs/STATUS.md
git commit -F - <<'EOF'
docs(status): 新增唯一状态源 STATUS.md

【为什么】
项目跨多次对话完成，"当前做到哪、下一步是什么"必须有单一存放点——
否则每次新对话都要靠翻 git log 猜，而猜测正是漂移的起点。这份文件
的"当前上下文文件"字段会被会话协议直接读取，是接手流程的第一跳。

【改了什么】
- docs/STATUS.md：当前阶段（M0）/ 当前上下文文件 / 里程碑进度表（M0–M11，
  按 spec §6.2 的新里程碑划分）/ M0 任务清单 / 证据等级图例 / 阻塞项 /
  下一步 / 待核实
- 里程碑表按新架构排序：M7 交易域、M8 秒杀、M9 服务化改造，而非旧的
  "M7 质量与交付、M8 AI 演进"

【验证】
- `ls docs/STATUS.md` → 文件存在
- 里程碑表的 12 行与 spec §6.2 的里程碑表逐行对照一致

【关联】
Refs: spec §6.2、§9 闸门 1
EOF

# 提交②：章程与协作协议
git add docs/00-charter/
git commit -F - <<'EOF'
docs(charter): 新增章程与协作协议

【为什么】
章程的作用是固定范围——"明确不做的事"必须逐条留档，因为范围蔓延
发生在每次"要不要加个功能"的讨论里，而没有书面依据时那种讨论没有
落点。协作协议则把会话开场/收场的动作固定下来，使交接不依赖记忆。

【改了什么】
- docs/00-charter/charter.md：提炼自 spec §1、§6.1，逐条保留"不做的事"
  （§6 一节是抵抗范围蔓延的依据，不得省略条目）
- docs/00-charter/collaboration.md：提炼自 spec §8、§9、§10，共 11 节；
  其中 §10「用户参与机制」（spec §8.5）与 §11「执行记录约定」（spec §8.6）
  是本次新增，且 §5「目录结构说明」按三域 12 模块重写，不从 spec §8.1 照抄

【验证】
- `ls docs/00-charter/` → charter.md、collaboration.md 均存在
- 人工核对 charter.md §6 与 spec §1.5 条目数一致（逐条不遗漏）
- `grep -c 'forum-module-' docs/00-charter/*.md` → 0（无旧模块名）

【关联】
Refs: spec §1、§1.5、§6.1、§8、§9、§10；architecture.md §3.1
EOF

# 提交③：术语表
git add docs/01-requirements/glossary.md
git commit -F - <<'EOF'
docs(requirements): 新增术语表，钉死易混术语

【为什么】
AI 中途换词是隐性漂移源：一会儿"帖子"一会儿"文章"，一会儿"付费专栏"
一会儿"课程"，代码里的类名就会跟着飘。业务域扩展后新增了交易与秒杀
两块术语（商品/订单/解锁凭证/超卖/回补），这些词在面试中是关键词，
写错就等于验收标准对不上。

【改了什么】
- docs/01-requirements/glossary.md：四组术语表——内容域业务术语、
  交易域与秒杀域术语、缓存与一致性技术术语（击穿/穿透/雪崩/幂等/
  本地消息表/对账）、服务与模块名（含"逻辑态 vs 物理态"的区分）
- 每条给出代码标识与"不要叫"的反例

【验证】
- `ls docs/01-requirements/glossary.md` → 文件存在
- 术语表中的模块名与 Task 1 的 12 个叶子模块名逐字对照一致

【关联】
Refs: spec §6.1、architecture.md §1–§3、ADR-0009
EOF
```

---

## Task 5: ADR 机制与 11 条 ADR 的索引

**Files:**
- Create: `docs/02-decisions/README.md`（ADR 索引，**权威**）
- Create: `docs/02-decisions/ADR-0001-tech-stack-baseline.md` ~ `ADR-0008-github-flow.md`（**8 条，本任务补写**）
- **不创建** `ADR-0009` / `0010` / `0011`——它们**已存在于仓库**（`ADR-0009-business-domain-expansion.md`、`ADR-0010-service-split.md`、`ADR-0011-consistency-strategy.md`）

**Interfaces:**
- Consumes: spec §2（决策摘要）、§12（开放问题）、`architecture.md` §8（与既有决策的关系）、已存在的 ADR-0009/0010/0011
- Produces: `docs/02-decisions/README.md` —— 被 CLAUDE.md 与 STATUS.md 引用；后续所有新决策在此追加

- [ ] **Step 0: 向用户确认**

① **这步做什么**：建立 ADR 机制（模板 + 何时写/不写 + 索引）并补写 0001–0008 共 8 条决策记录；索引里把**已有的 0009/0010/0011 一并列出**，凑成 11 条。

② **为什么现在做**：架构大改产生了 3 条新决策（业务域扩展、服务拆分、一致性方案），并且**推翻了两条旧决策**——ADR-0002（架构形态）已被修订、ADR-0005（不用微服务）已废弃。spec §8.3 铁律 2 要求"推翻旧结论必须新增 ADR，禁止静默修改"。若不同时把 0001–0008 补齐并更新索引，**0002 与 0005 就会以"已接受"的假状态留在索引里**，下一个对话会照着"不做微服务"的旧结论干活——这是本项目最危险的一类漂移。

③ **做法与建议**：
- **方案 A（建议）**：索引列全 11 条，8 条本次补写并逐条标注真实状态（0002「已修订」、0005「已废弃」），0009/0010/0011 标注「已存在」并链接。代价：要写 8 份文件（每份约 40 行），是本任务最重的一步。
- 方案 B：只索引不写正文，等 M1 补。代价：索引会指向 8 个不存在的文件，Task 6 的 `check-doc-refs.sh` 会（正确地）报漂移。
- 方案 C：把 0002/0005 直接改写成新结论。**违反铁律 2**——推翻旧结论要新增 ADR，不是改写旧文件，否则历史痕迹消失、面试时讲不出"我改过主意、原因是……"。

**建议 A，等用户回应后再进 Step 1。**

- [ ] **Step 1: 写 ADR 模板与索引 `README.md`**

`docs/02-decisions/README.md`：

```markdown
# 架构决策记录（ADR）索引

> **本索引是 ADR 的权威清单。** spec §2 的决策表是 2026-09-13 的快照，
> 与本索引不一致时**以本索引为准**。

## 什么时候写 ADR

满足任一条即写：

- 在两个以上方案间做了选择（哪怕结论是"不做"）
- 推翻或修正了此前的决策
- 引入/排除了一个依赖、模式或架构形态
- 做了会被面试官追问"为什么"的技术决定

## 什么时候不写

- 实现细节（命名、格式、局部重构）—— 那些写进 commit message
- 纯粹的偏好（用 4 空格还是 2 空格）

## 命名与状态

- 文件名：`ADR-NNNN-<kebab-case-标题>.md`，编号四位、单调递增、**永不复用**
- 状态：`提议` → `已接受` / `已修订` / `已废弃` / `被 ADR-NNNN 取代`
- **`已修订` 的用法**（本项目已有实例）：决策的**结论被部分修改但方向未变**时用「已修订」，并在文件头部加一行 `- **修订**：ADR-NNNN（修订了什么）`；**推翻整个结论**才用「已废弃」+`- **取代**：ADR-NNNN`。**两种都必须在原文件追加「修订记录」小节，禁止直接改写原文**——否则面试时讲不出"我为什么改主意"

## 模板

```markdown
# ADR-NNNN: <决策标题>

- **状态**：已接受
- **日期**：YYYY-MM-DD
- **决策者**：Wingtisky（决策） / Claude（建议）

## 背景

<什么问题需要决策？约束是什么？>

## 候选方案

### 方案 A：<名称>
- 优点：
- 缺点：

### 方案 B：<名称>
- 优点：
- 缺点：

## 决定

<选了哪个，一句话说清。>

## 理由

<为什么选它。这是最重要的一节——面试官问的就是这里。>

## 后果

- **正面**：
- **负面**：<诚实写出代价。没有代价的决策通常是没想清楚。>

## 被否决方案为何不选

<逐个说明。没有这一节，ADR 只有一半价值。>
```

## 索引

> **共 11 条。** 状态列的最后三行是 2026-09-13 架构变更的产物，其中 **0002 已修订、0005 已废弃**——`spec §2` 的决策表仍写着它们"已接受"，**以本索引为准**。

| 编号 | 标题 | 状态 | 日期 | 备注 |
|---|---|---|---|---|
| [ADR-0001](ADR-0001-tech-stack-baseline.md) | 技术栈基线：JDK17 + Boot3 + ES8 + Kafka KRaft | 已接受 | 2026-09-13 | 本任务新建 |
| [ADR-0002](ADR-0002-modular-monolith.md) | 架构形态：模块化单体 | **已修订** | 2026-09-13 | 本任务新建；被 ADR-0010 修订为「多服务 + 先画逻辑边界后做物理拆分」，**模块边界规则不变** |
| [ADR-0003](ADR-0003-frontend-vue3.md) | 前端形态：Vue3 + Element Plus 前后端分离 | 已接受 | 2026-09-13 | 本任务新建 |
| [ADR-0004](ADR-0004-no-docker-locally.md) | 本地不使用 Docker | 已接受 | 2026-09-13 | 本任务新建 |
| [ADR-0005](ADR-0005-no-microservices.md) | 明确不采用微服务 | **已废弃** | 2026-09-13 | 本任务新建；**由 ADR-0010 取代**——业务域扩展后前提改变 |
| [ADR-0006](ADR-0006-delivery-github-public.md) | 交付形态：GitHub 公开 + 本地可复现 | 已接受 | 2026-09-13 | 本任务新建 |
| [ADR-0007](ADR-0007-integration-test-not-in-ci.md) | 集成测试不在 CI 中运行 | 已接受 | 2026-09-13 | 本任务新建 |
| [ADR-0008](ADR-0008-github-flow.md) | 分支模型：GitHub Flow + Squash merge | 已接受 | 2026-09-13 | 本任务新建 |
| [ADR-0009](ADR-0009-business-domain-expansion.md) | 业务域扩展：纯论坛 → 技术社区 + 付费内容 | 已接受 | 2026-09-13 | **已存在，不重写** |
| [ADR-0010](ADR-0010-service-split.md) | 按故障域拆三个服务 + 先边界后进程 | 已接受 | 2026-09-13 | **已存在，不重写**；修订 ADR-0002、取代 ADR-0005 |
| [ADR-0011](ADR-0011-consistency-strategy.md) | 跨服务一致性：本地消息表 + MQ 最终一致 | 已接受 | 2026-09-13 | **已存在，不重写**；明确不用 Seata |
```

- [ ] **Step 2: 写 ADR-0001（技术栈基线）**

来源：spec §3、附录 A 的环境实测。**必须包含**以下要点：
- 背景：本机已装 JDK 17 / Redis 5 / MySQL 8 / Kafka 2.8 / ES 6.8；旧项目用 Boot 2 + JDK 11
- 方案 A：全套沿用旧栈（JDK 11 + Boot 2 + ES 6.8）
- 方案 B：半现代（Boot 3 + ES 8，Kafka 2.8 不动）
- 方案 C（选中）：JDK 17 + Boot 3.5.16 + ES 8.x + Kafka 3.9 KRaft
- 理由核心：**Spring AI / LangChain4j 硬性要求 Java 17+**，M11 的 AI 演进若沿用旧栈必须大返工
- 硬约束：**Spring Boot 3.x 官方 ES 客户端只面向 ES 7.17/8.x**，ES 6.8 的 TransportClient 在 ES 8 已彻底移除
- 后果负面：本机需新装 ES 8 + Kafka 3.9，约多占 1.5G 内存；环境成本约半天
- **追加节「修订记录」**（面试时的加分点）：① 2026-09-13 把补丁版从 3.5.3 更正为 3.5.16——初版用 solr 的 `core=gav` top-N 查版本，**它按发布时间排序而非版本号**，返回了过期版本，后改为读 `maven-metadata.xml` 核实；② 留一条待办：Redisson 与 Redis 5.0.14.1 的兼容性待 M3 实证，不兼容则降级并记在此处

- [ ] **Step 3: 写 ADR-0002（模块化单体，状态「已修订」）**

来源：spec §4、`architecture.md` §2、已存在的 ADR-0010。**必须包含**：
- 三个候选：A 模块化单体（选中）/ B 单模块分层 / C 微服务
- 理由核心：**"知道怎么解耦"和"知道什么时候不解耦"**，后者更难也更是面试官想听的
- 后果负面：模块边界靠自律，用 Maven 约束 + ArchUnit 强制；多模块构建配置繁琐
- 附：**按业务域切分 vs 按技术分层切分**的取舍说明（结论：按业务域，与三个服务边界对齐）
- **状态行写「已修订」**，头部加 `- **修订**：ADR-0010（架构形态由「模块化单体」修订为「多服务 + 先画逻辑边界后做物理拆分」）`
- **末尾追加「修订记录（2026-09-13）」小节**：说明① 结论被修订的原因（业务域扩展 + 服务拆分后，模块清单由 9 个变为三域 12 模块，见 `architecture.md` §3.1）；② **哪些没变**——「模块边界规则（域间不互相依赖、domain 只放契约、唯一启动模块）全部保留，ADR-0010 修订的是"要不要拆进程"，不是"要不要划边界"」。**这一句是本节存在的理由**：面试官看到「已修订」时第一反应是"那你原来的设计是不是错了"，必须有明确回答

- [ ] **Step 4: 写 ADR-0003 ~ ADR-0008**

每条按模板写，来源与核心论点如下：

| ADR | 来源 | 必须写进"被否决方案为何不选"的内容 |
|---|---|---|
| 0003 前端 | spec §2 | React（对你目标岗位收益不明显）、无构建静态前端（拿不到框架关键词）、Thymeleaf（与你要求的分离相悖且与旧项目同质） |
| 0004 不用 Docker | spec §2、附录 A | Docker Compose（本机无 Docker/WSL2，16G 内存叠虚拟化拖垮体验）。**必须写清"GitHub 用户可用 Docker Compose 复现"这一保留** |
| 0005 不用微服务 | spec §1.5、§2 | Nacos + 网关方案。**必须写出"什么条件下才该拆微服务"**——这是面试官最可能追问的。**状态行写「已废弃」**，头部加 `- **取代**：ADR-0010`；末尾追加「废弃记录（2026-09-13）」说明前提如何改变（当时判断"本机跑不动且非亮点"；引入付费内容与秒杀后出现了两个流量特征截然不同的域，拆分理由从"跟风"变成"故障域隔离"——**注意这不是"当初判断错了"，而是"当初的约束变了"**，这个区分在面试时很有用） |
| 0006 交付形态 | spec §2 | 公网部署（需多 1-2 周 + 服务器成本，4G 机器跑 ES+Kafka 吃紧）。**保留**：架构上已保证配置外置，后期上云不返工 |
| 0007 集成测试不进 CI | spec §7.2、§9 | Testcontainers（标准做法，但本机无 Docker 跑不了）。**必须附"如果有 Docker 应该怎么做"**，并记下 M7/M10 的评估结论 |
| 0008 Git 流程 | spec §10.2 | Git Flow（对单人项目过重）、直接 push main（无 PR 即无变更记录） |

> **0003~0008 的状态一律是「已接受」**，不受本次架构变更影响。**除 0002 与 0005 外，本任务不写任何「修订记录」**——没有真的改过，就不要编出改动痕迹。

- [ ] **Step 5: 验证 ADR 完整性**

Run:
```bash
ls docs/02-decisions/ | grep -c '^ADR-'
grep -c '^| \[ADR-' docs/02-decisions/README.md
grep -c '已修订' docs/02-decisions/README.md
grep -c '已废弃' docs/02-decisions/README.md
```
Expected:
- 前两条均为 **11**（11 个 ADR 文件 = 11 条索引）；
- 第三条 ≥ 1（0002 标「已修订」）；
- 第四条 ≥ 1（0005 标「已废弃」）。

**若第一条是 11 但索引只有 8**，说明漏了 0009/0010/0011 三行——它们**已存在于仓库**，索引漏列会让 `check-doc-refs.sh`（正确地）认为文档与代码对不上。

- [ ] **Step 6: 提交**

**本任务拆 3 个提交**，按"机制 / 补写 / 状态变更"拆：索引与模板是机制；0001/0003/0004/0006/0007/0008 是补写（新增内容）；0002 与 0005 是**状态改写**（推翻旧结论）——后者单独成条的理由是**回滚语义不同**：若将来发现状态改错了，只需回滚这一条，而不该连带丢掉六条新写的 ADR。

```bash
# 提交①：索引与模板（机制）
git add docs/02-decisions/README.md
git commit -F - <<'EOF'
docs(adr): 建立 ADR 索引与模板，索引至 11 条

【为什么】
面试官问的是"你为什么这么设计"，而不是"你做了什么"。ADR 索引是这套
机制的权威清单——spec §2 的决策表是快照且已开始与事实不符（见下）。索引
先行，后续每条 ADR 才有归属；且 Task 6 的 check-doc-refs.sh 会检查
索引里的路径是否真实存在，索引与文件必须一一对应。

【改了什么】
- docs/02-decisions/README.md：何时写/不写 ADR、命名与状态机
  （补「已修订」的用法与"禁止改写原文"的约束）、ADR 模板
- 索引列全 11 条：0001-0008 标注"本任务新建"，0009-0011 标注"已存在，不重写"
- 在 README 中声明：本索引是权威，与 spec §2 不一致时以本索引为准

【验证】
- `grep -c '^| \[ADR-' docs/02-decisions/README.md` → 11
- `ls docs/02-decisions/` → 11 个 ADR 文件

【关联】
Refs: spec §2、§8.3 铁律 2
EOF

# 提交②：补写 6 条新 ADR
git add docs/02-decisions/ADR-0001-tech-stack-baseline.md \
        docs/02-decisions/ADR-0003-frontend-vue3.md \
        docs/02-decisions/ADR-0004-no-docker-locally.md \
        docs/02-decisions/ADR-0006-delivery-github-public.md \
        docs/02-decisions/ADR-0007-integration-test-not-in-ci.md \
        docs/02-decisions/ADR-0008-github-flow.md
git commit -F - <<'EOF'
docs(adr): 补写 ADR-0001/0003/0004/0006/0007/0008 正文

【为什么】
这 6 条决策此前只存在于 spec §2 的汇总表里——一张表格放不下
"候选方案 / 后果（含负面）/ 被否决方案为何不选"三节，而这三节
恰恰是面试官追问的落点。补写正文的目的是把"我这么做了"补成
"我为什么没选另一个方案"。

【改了什么】
- ADR-0001 技术栈基线：新增「修订记录」——版本核查手段的缺陷
  （solr core=gav top-N 按发布时间排序，把 3.5.3 误判为最新；
  已改为读 maven-metadata.xml），并留 Redisson/Redis 5 兼容性待办
- ADR-0003 前端 Vue3 / ADR-0004 不用 Docker（含"GitHub 用户可用
  Docker Compose 复现"的保留）/ ADR-0006 交付形态
- ADR-0007 集成测试不进 CI（附"如果有 Docker 应该怎么做"）
- ADR-0008 GitHub Flow + Squash merge
- 每条均含"后果（含负面）"与"被否决方案为何不选"两节

【验证】
- `ls docs/02-decisions/ADR-000[134678]*` → 6 个文件存在
- 每条 ADR 均有「理由」「后果」「被否决方案为何不选」三节

【关联】
Refs: spec §2、§3、§7.2、§10.2、附录 A
EOF

# 提交③：两条旧决策的状态变更（变更留痕）
git add docs/02-decisions/ADR-0002-modular-monolith.md \
        docs/02-decisions/ADR-0005-no-microservices.md
git commit -F - <<'EOF'
docs(adr): 标注 ADR-0002 已修订、ADR-0005 已废弃

【为什么】
spec §8.3 铁律 2：推翻旧结论必须新增 ADR 并留痕，禁止静默修改。
架构大改后，ADR-0005「不采用微服务」的前提已不成立（业务域扩展后
出现了两个流量特征截然不同的域，拆分理由从"跟风"变成"故障域隔离"），
ADR-0002 的架构形态也被修订。若不同步状态，spec §2 的表中它们仍写
"已接受"，下一个对话会照着"不做微服务"的旧结论干活——这是本项目
最危险的一类漂移。

【改了什么】
- ADR-0002：状态改「已修订」+ 头部指向 ADR-0010 + 末尾「修订记录」小节，
  写明"模块边界规则（域间不依赖、domain 只放契约、唯一启动模块）全部保留，
  变的是'要不要拆进程'"——这一句是回应"你原来的设计是不是错了"
- ADR-0005：状态改「已废弃」+ 头部「取代：ADR-0010」+ 末尾「废弃记录」，
  写明"不是当初判断错了，而是当初的约束变了"
- 两条均不改写原文，只在末尾追加记录

【验证】
- `grep -n '已修订' docs/02-decisions/ADR-0002-*.md` → 命中状态行
- `grep -n '已废弃' docs/02-decisions/ADR-0005-*.md` → 命中状态行

【关联】
Refs: ADR-0010、spec §8.3 铁律 2、architecture.md §8
EOF
```

> **为什么从单条提交改为三条**（spec §10.4.1）：三条提交的回滚语义不同——索引/模板是机制、6 条正文是新增内容、0002/0005 是状态改写。若混在一起，将来发现状态改错了要回滚，会连带丢掉 6 条新写的 ADR。

---

## Task 6: 防漂移脚本 tools/

**Files:**
- Create: `tools/check-deps.sh`
- Create: `tools/check-doc-refs.sh`
- Create: `tools/README.md`

**Interfaces:**
- Consumes: Task 1 的 POM、Task 4/5 的 docs
- Produces: 两个可执行脚本，退出码 `0`=通过 / 非 `0`=发现问题。**Task 7 的 CI 直接调用它们**

**为什么用 shell 而不是 Java/Maven 插件**：遵循 minimal tooling。两个脚本合计 <100 行，不需要引入任何构建插件。

- [ ] **Step 0: 向用户确认**

① **这步做什么**：写两个只读校验脚本——`check-deps.sh`（依赖能否真实解析 + 版本号有没有散落在子模块）+ `check-doc-refs.sh`（文档里反引号引用的类/包在代码里是否真实存在），以及 `tools/README.md`。

② **为什么现在做**：spec §9 闸门 3 与闸门 6 目前只有原则没有手段。**本次架构重排正是这两类问题的现场**：12 个模块的新坐标（`wt-*`、`trade-*`…）如果拼错，Maven 直接失败还好；但文档里写着 `forum-module-user` 而代码里已是 `forum-user`，**没有任何东西会报错**，只会让下一个对话照着错名字干活。而且它必须早于 Task 7 的 CI——CI 要调用它们。

③ **做法与建议**：
- **方案 A（建议）**：两个 bash 脚本，进 CI。代价：脚本用 `grep` 做文本匹配，会漏掉"模块改名但文档没改"之外的语义漂移；且 Windows 上需 Git Bash 运行。
- 方案 B：写成 Maven 插件（`maven-enforcer-plugin` 等）。代价：引入构建插件，违反 minimal tooling（spec §1.5），且插件自身版本也是新的幻觉风险源。
- 方案 C：不做脚本，靠 CI 里手写 `grep` 步骤。代价：本地跑不了，M0 闸门"本地可复现"失效。

**建议 A，等用户回应后再进 Step 1。**

- [ ] **Step 1: 写 `tools/check-deps.sh`**

```bash
#!/usr/bin/env bash
# 依赖真实性核查（spec §9 闸门 3）
# 用途：确认所有依赖真实可解析，防止 package hallucination
set -euo pipefail

cd "$(dirname "$0")/.."

echo "[check-deps] 解析全部依赖（含传递依赖）..."
if ! mvn -q -B dependency:resolve dependency:resolve-plugins; then
  echo "[check-deps] 失败：存在无法解析的依赖。请核实坐标是否真实存在于 Maven Central。"
  exit 1
fi

echo "[check-deps] 检查父 POM 之外的散落版本号..."
# 子模块 POM 中不允许出现 <version>（除 parent 段），版本必须收在父 POM 的 dependencyManagement
violations=$(grep -rn --include=pom.xml '<version>' . \
  | grep -v '/pom.xml:.*<version>1.0.0-SNAPSHOT</version>' \
  | grep -v 'spring-boot-starter-parent' \
  | grep -v 'project.build' || true)

if [ -n "$violations" ]; then
  echo "[check-deps] 失败：以下位置存在散落的版本号，应移入父 POM 的 dependencyManagement："
  echo "$violations"
  exit 1
fi

echo "[check-deps] 通过。"
```

- [ ] **Step 2: 写 `tools/check-doc-refs.sh`**

```bash
#!/usr/bin/env bash
# 文档-代码一致性核查（spec §9 闸门 6）
# 用途：防止文档漂移——文档里提到的类名/方法名/包名，必须在代码中真实存在
set -uo pipefail

cd "$(dirname "$0")/.."

DOCS_DIR="docs"
# 12 个叶子模块（三个域聚合目录 forum/ trade/ seckill/ 本身不含 .java，不列入）
SRC_DIRS=(wt-common wt-domain wt-infra \
          forum/forum-user forum/forum-content forum/forum-search forum/forum-notify \
          trade/trade-product trade/trade-order trade/trade-payment \
          seckill/seckill-coupon \
          app)

# 从文档中提取被反引号包裹、且形如 Java 全限定/类名/包的标识符
# 只检查三类高风险引用，避免误报：
#   1) com.wingtisky.* 全限定名
#   2) XxxApplication / XxxService / XxxMapper 等以已知后缀结尾的类名
#   3) 已存在的模块目录名
refs=$(grep -rhoE '`(com\.wingtisky\.[A-Za-z0-9_.]+|[A-Z][A-Za-z0-9]*(Application|Service|Mapper|Controller|Config|Util|Test))`' \
        "$DOCS_DIR" 2>/dev/null | tr -d '`' | sort -u || true)

if [ -z "$refs" ]; then
  echo "[check-doc-refs] 文档中暂无可检查的代码引用。通过。"
  exit 0
fi

missing=0
while IFS= read -r ref; do
  [ -z "$ref" ] && continue
  # 全限定名 → 转成路径查找；简单类名 → 全仓库查找
  search=$(echo "$ref" | tr '.' '/')
  if ! grep -rq "$(basename "$search")" "${SRC_DIRS[@]}" --include='*.java' 2>/dev/null; then
    echo "[check-doc-refs] 漂移：文档引用了 '$ref'，但代码中不存在。"
    missing=$((missing + 1))
  fi
done <<< "$refs"

if [ "$missing" -gt 0 ]; then
  echo "[check-doc-refs] 失败：$missing 处文档引用在代码中不存在。"
  exit 1
fi

echo "[check-doc-refs] 通过。"
```

- [ ] **Step 3: 写 `tools/README.md`**

```markdown
# tools/ — 防漂移校验脚本

只读校验，**不修改任何代码**。被 `.github/workflows/ci.yml` 调用。

| 脚本 | 职责 | 对应闸门 |
|---|---|---|
| `check-deps.sh` | 依赖真实可解析 + 版本号不散落 | spec §9 闸门 3 |
| `check-doc-refs.sh` | 文档中引用的类/包在代码中真实存在 | spec §9 闸门 6 |

## 本地运行

```bash
bash tools/check-deps.sh
bash tools/check-doc-refs.sh
```

退出码 `0` = 通过。

## 为什么是 shell 而非 Maven 插件

遵循 minimal tooling 原则（spec §1.5）。两个脚本合计不足 100 行，
引入构建插件反而增加维护面与幻觉风险。
```

- [ ] **Step 4: 验证两个脚本可运行**

Run:
```bash
chmod +x tools/*.sh
bash tools/check-deps.sh; echo "check-deps 退出码: $?"
bash tools/check-doc-refs.sh; echo "check-doc-refs 退出码: $?"
```
Expected:
- `check-deps` → `[check-deps] 通过。` 退出码 `0`
- `check-doc-refs` → 应报告 `CLAUDE.md` 中引用的 `ArchitectureTest` 尚不存在（**这是预期的正确行为**——Task 7 才创建该类，属"文档先行、代码未到"）。进入 Step 5 处理；**若报其他项，逐条核实文档是否真的写错了引用**（很可能是 Task 1–5 遗留的旧模块名）。

- [ ] **Step 5: 处理 Step 4 发现的漂移**

- 若报 `ArchitectureTest` 不存在：**不要删掉引用**——该引用在 CLAUDE.md 中标注了完整路径，是 Task 7 将要创建的东西，**留待 Task 7 补齐**，本任务到此结束。
- 若报 `forum-module-*` / `forum-boot` / `forum-common` 等**旧模块名**：说明 Task 1–5 某处漏改，回到对应文件改成新模块名后重跑。
- **若 Step 4 无报错：说明脚本的匹配规则太窄（该报的没报）**，检查 `refs` 提取正则是否被 shell 转义吃掉了——这比"报了假阳性"更危险。

- [ ] **Step 6: 提交**

**本任务拆 2 个提交**：先提两个脚本（工具本体），再提 `tools/README.md`（说明文档）。理由：脚本是机制，README 是对人的使用说明，回滚时机不同（改说明不该动脚本）。若执行时判断 README 太短（< 30 行）不足以独立成条，**合并为 1 个提交并在 `【为什么】` 里说明原因**——但不要因为"懒得分"就合并。

```bash
git add tools/check-deps.sh tools/check-doc-refs.sh
git commit -F - <<'EOF'
feat(tools): 新增依赖与文档-代码一致性核查脚本

【为什么】
spec §9 闸门 3 与闸门 6 此前只有原则没有手段：依赖幻觉（引用了不存在
的库）和文档漂移（文档描述了一个不存在的类）都无法自动发现。本次
模块结构重排正是这两类问题的高发现场——12 个新坐标（wt-*、trade-*）
若只拼错在文档里，没有任何构建步骤会失败，只会让下一个对话照着
错名字干活。

【改了什么】
- tools/check-deps.sh：mvn dependency:resolve 验证依赖可解析 +
  检查父 POM 之外是否存在散落版本号
- tools/check-doc-refs.sh：提取文档中形如 com.wingtisky.* 与
  Xxx{Application,Service,Mapper,Controller,Config,Util,Test} 的引用，
  校验其在 Java 源码中真实存在；SRC_DIRS 按新结构列出 12 个叶子模块

【验证】
- `bash tools/check-deps.sh` → [check-deps] 通过，退出码 0
- `bash tools/check-doc-refs.sh` → 仅报 CLAUDE.md 引用的 ArchitectureTest
  尚不存在（Task 7 才创建，属预期的文档先行）

【关联】
Refs: spec §9 闸门 3、闸门 6
EOF

git add tools/README.md
git commit -F - <<'EOF'
docs(tools): 补充 tools/ 使用说明与设计取舍

【为什么】
两个脚本的"为什么是 shell 而不是 Maven 插件"是本项目 minimal tooling
原则的具体体现，也是最容易被后来者（或 AI）推翻的决定——不加说明的话，
下一个人看到两个 bash 脚本，很自然地会提议"不如写成 Maven 插件"，
然后再引入一个新插件依赖。把取舍写下来，是防止这次决策被重做的唯一手段。

【改了什么】
- tools/README.md：脚本职责表（对应哪道闸门）、本地运行方式与退出码约定、
  为何是 shell 而非 Maven 插件的理由

【验证】
- `bash tools/README.md` 中的两条命令可直接复制执行（已在本任务 Step 4 跑过）

【关联】
Refs: spec §9 闸门 3/6、§1.5（minimal tooling）
EOF
```

---

## Task 7: 架构边界测试 + CI 骨架

**Files:**
- Create: `app/src/test/java/com/wingtisky/forum/ArchitectureTest.java`
- Create: `.github/workflows/ci.yml`
- Create: `README.md`

**Interfaces:**
- Consumes: Task 1 的模块结构、Task 6 的两个脚本
- Produces: CI 流水线（push/PR 触发）；`README.md` 作为仓库门面

- [ ] **Step 0: 向用户确认**

① **这步做什么**：写 5 条 ArchUnit 规则把域边界变成测试（外加 1 条可选的「唯一启动类」规则），再补 CI 与 README。规则的核心是**三个域两两互不依赖**。

② **为什么现在做**：spec §4.2 说边界"不靠自觉"。**本次架构的核心价值就是那条域边界**（`forum/**`、`trade/**`、`seckill/**` 两两互斥）——没有它，"三个服务"只是文档里的三行标题；M9 物理拆分时才发现代码早就缠在一起，拆分收益归零。而且规则要趁模块还空着落地：空模块的基线是绿的，**任何一个域间依赖都是这之后新增的，一定是违规**，不会被"存量违规"稀释。

③ **做法与建议**：
- **方案 A（建议）**：5 条规则 + 保留"故意写违规类验证规则真会失败"这一步。代价：要写一个临时类再删掉，多两轮 `mvn`。收益：证明规则不是装饰。
- 方案 B：写规则但不做违规验证。代价：**ArchUnit 的包通配符写错时，规则会永远通过**（例如给 `..forum.forum..` 少写一个点），而你要到 M1 之后才会发现——那时已经晚了。
- 方案 C：不写 ArchUnit，靠 Maven 依赖约束（域间不加依赖即可）。代价：Maven 只管模块级依赖，管不了"包内 import"；且 `app` 必须依赖三个域，所以域间依赖一旦出现在 `app` 就无人拦截。

**建议 A，等用户回应后再进 Step 1。**

- [ ] **Step 1: 写架构测试**

`app/src/test/java/com/wingtisky/forum/ArchitectureTest.java`：

```java
package com.wingtisky.forum;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构边界规则的强制检查（spec §4.2、architecture.md §3.1）。
 *
 * <p>这些规则不是"建议"——违反即测试失败。目的是让"三个域"的边界从
 * 文档里的一句话变成 CI 能拦住的硬约束：M9 物理拆分的收益，取决于
 * M0–M8 期间这条线有没有被守住。
 *
 * <p>包名对应关系（改包名 = 规则失效，见 Task 1 Step 5）：
 * <ul>
 *   <li>{@code com.wingtisky.forum.common}   —— wt-common</li>
 *   <li>{@code com.wingtisky.forum.domain}   —— wt-domain</li>
 *   <li>{@code com.wingtisky.forum.infra}    —— wt-infra</li>
 *   <li>{@code com.wingtisky.forum.forum..}  —— forum/** 内容域</li>
 *   <li>{@code com.wingtisky.forum.trade..}  —— trade/** 交易域</li>
 *   <li>{@code com.wingtisky.forum.seckill..}—— seckill/** 秒杀域</li>
 * </ul>
 */
class ArchitectureTest {

    /** 三个业务域，两两互斥。这是本次架构的核心边界。 */
    private static final String[] DOMAINS = {
            "..forum.forum..", "..forum.trade..", "..forum.seckill.."
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.wingtisky.forum");
    }

    @Test
    @DisplayName("规则1：wt-common 不得依赖任何业务域")
    void commonShouldNotDependOnAnyDomain() {
        noClasses().that().resideInAPackage("..forum.common..")
                .should().dependOnClassesThat().resideInAnyPackage(DOMAINS)
                .because("wt-common 是最底层工具，依赖业务域会造成环")
                .check(classes);
    }

    @Test
    @DisplayName("规则2：三个业务域之间两两不得互相依赖")
    void domainsShouldNotDependOnEachOther() {
        for (String from : DOMAINS) {
            noClasses().that().resideInAPackage(from)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            Arrays.stream(DOMAINS)
                                    .filter(to -> !to.equals(from))
                                    .toArray(String[]::new))
                    .because("域间必须通过 wt-domain 的契约接口通信，不得直接依赖——"
                            + "这条线是 M9 物理拆分能成立的前提")
                    .check(classes);
        }
    }

    @Test
    @DisplayName("规则3：wt-domain 不得依赖 wt-infra（契约不依赖实现）")
    void domainShouldNotDependOnInfra() {
        noClasses().that().resideInAPackage("..forum.domain..")
                .should().dependOnClassesThat().resideInAPackage("..forum.infra..")
                .because("wt-domain 只放契约，反向依赖基础设施实现会让契约被实现绑死")
                .check(classes);
    }

    @Test
    @DisplayName("规则4：wt-infra 不得依赖任何业务域")
    void infraShouldNotDependOnAnyDomain() {
        noClasses().that().resideInAPackage("..forum.infra..")
                .should().dependOnClassesThat().resideInAnyPackage(DOMAINS)
                .because("infra 放的是机制，机制一旦知道业务策略就不再是机制")
                .check(classes);
    }

    @Test
    @DisplayName("规则5：启动类只允许存在于 app 模块（可选规则）")
    void onlyAppShouldDeclareSpringBootApplication() {
        // 检查"被 @SpringBootApplication 标注的类"必须位于 com.wingtisky.forum 根包
        // （即 app 模块的位置）。M9 拆出 forum-app/trade-app/seckill-app 时，
        // 本规则的 importPackages 范围需要同步调整——那时它才真正开始发挥作用。
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .classes().that().areAnnotatedWith(
                        org.springframework.boot.autoconfigure.SpringBootApplication.class)
                .should().resideInAPackage("com.wingtisky.forum")
                .because("M0–M8 只允许 app 一个进程；多出启动类意味着有人提前拆了进程")
                .check(classes);
    }
}
```

> **关于规则 5（可选规则）**：它在 M0 是**恒真**的——当前只有 `WingtiskyForumApplication` 一个启动类且位于根包。它的价值在 M9：物理拆分需要把启动类从 `app` 搬到 `forum-app` / `trade-app` / `seckill-app` 三个模块，届时若忘了调整这条规则，测试会失败并提醒"规则与结构已不一致"。**若执行时判断这条规则难以维护（例如 `importPackages` 范围写死导致 M9 必改），可以省略它并在提交信息中说明**——省掉一条不会失去核心边界保护（规则 1–4 才是核心），但**不能省掉规则 2**。

- [ ] **Step 2: 运行测试，确认基线通过**

Run: `mvn -q -pl app -am test -Dtest=ArchitectureTest`
Expected: 4–5 个测试全部 PASS（取决于是否保留规则 5）。

**为什么此时期望通过而非失败**：当前模块是空的，没有违规可抓。这些规则的价值在 M1 之后开始体现——它们是**守卫**而非**待办**。若此时失败，最可能的原因是**包名通配符写错**（例如把 `..forum.forum..` 误写成 `..forum..`——后者会匹配到 `wt-common` 所在的 `com.wingtisky.forum.common`，从而报出大量假违规）。

- [ ] **Step 3: 验证规则真的能抓住违规（关键步骤，不可省略）**

**这是防止"测试永远通过从而形同虚设"的必要验证。** 本次架构的边界是**域与域之间**，所以违规实验必须打在域边界上——用**交易域依赖内容域**：

创建临时文件 `trade/trade-order/src/main/java/com/wingtisky/forum/trade/order/TempViolation.java`：

```java
package com.wingtisky.forum.trade.order;

import com.wingtisky.forum.forum.user.PlaceholderInUser;

/** 临时违规类：仅用于验证 ArchUnit 的域边界规则生效，验证后立即删除。 */
public class TempViolation {
    private PlaceholderInUser ref;
}
```

同时在内容域创建被依赖方 `forum/forum-user/src/main/java/com/wingtisky/forum/forum/user/PlaceholderInUser.java`：

```java
package com.wingtisky.forum.forum.user;

/** 临时类：仅用于验证 ArchUnit 规则生效，验证后立即删除。 */
public class PlaceholderInUser {
}
```

Run: `mvn -q -pl app -am test -Dtest=ArchitectureTest`
Expected: **FAIL**，报错指出 `com.wingtisky.forum.trade.order.TempViolation` 依赖了 `..forum.forum..`（`trade → forum` 跨域依赖）。

若通过 → **规则失效，必须先修好再继续**（检查 `DOMAINS` 数组的包名通配符与 `importPackages` 范围）。

> **为什么违规实验选 trade→forum 而不是其他组合**：这是最可能真实发生的一种跨域依赖——订单需要用户信息，最省事的写法就是 `import` 用户类。用这个组合做实验，等于顺带验证了"最危险的那条路径确实被拦住了"。
>
> **另需验一条**：再临时把 `TempViolation` 里的 `import` 改成 `com.wingtisky.forum.trade.product.PlaceholderInTradeProduct`（同域内），确认此时**测试通过**——证明规则拦的是"跨域"，不是"任何依赖"。**只验失败不验通过，无法区分"规则正确"与"规则一刀切"。**

- [ ] **Step 4: 删除临时类并复测**

```bash
rm trade/trade-order/src/main/java/com/wingtisky/forum/trade/order/TempViolation.java
rm forum/forum-user/src/main/java/com/wingtisky/forum/forum/user/PlaceholderInUser.java
# 若为验证"同域依赖应通过"临时加过 trade-product 的占位类，一并删除
mvn -q -pl app -am test -Dtest=ArchitectureTest
```
Expected: 全部测试 PASS（回到基线）。

> **为什么坚持做 Step 3**：一个从未失败过的测试不是测试，是装饰。spec §9 闸门 2 要求"反面对照"——这条规则实验就是它的体现。**并且本步骤的顺序不可颠倒**：必须先确认基线绿、再确认违规红、最后确认删除后回到绿；只做前两步而跳过第三步，就无法排除"临时类根本没被编译进去"这种假象。

- [ ] **Step 5: 写 CI 配置**

`.github/workflows/ci.yml`：

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: 检出代码
        uses: actions/checkout@v4

      - name: 配置 JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: 编译
        run: mvn -B -q clean compile

      - name: 单元测试（架构规则测试也在此）
        run: mvn -B test

      - name: 依赖真实性核查（闸门 3）
        run: bash tools/check-deps.sh

      - name: 文档-代码一致性核查（闸门 6）
        run: bash tools/check-doc-refs.sh
```

> **注意**：CI 中**不运行集成测试**（ADR-0007）。当前无集成测试，此约定在 M1 后由 `@Tag("integration")` 落实。

- [ ] **Step 6: 写 `README.md`**

`README.md` 必须包含以下章节（内容如实填写，**不要写还没实现的功能**）：

```markdown
# Wingtisky Forum

<一句话简介：技术社区 + 付费内容（CSDN 形态），Java 后端项目>

## 这是什么
<2-3 句：技术社区 + 付费内容，内容 → 付费 → 交易 → 解锁的闭环；用于展示后端工程能力>

## 技术栈
<表格，与 CLAUDE.md 的技术栈白名单一致>

## 架构
<模块表格，按三个域组织：wt-common / wt-domain / wt-infra + forum / trade / seckill + app>
<一句话说明边界规则：三个域两两不得互相依赖，跨域走 wt-domain 的契约，由 ArchUnit 强制>
<注明：M0–M8 是「逻辑态」（一个进程），M9 才拆为 forum-app / trade-app / seckill-app + gateway>

## 本地运行
<前置要求：JDK 17、Maven、MySQL 8、Redis 5、Kafka 3.9、ES 8>
<步骤：1. 启动中间件 2. 配置环境变量 3. mvn -pl app -am spring-boot:run>
<注意：中间件安装详见 docs/06-runbook/local-setup.md>

## 项目状态
<如实标注：M0 进行中（骨架阶段，业务功能未实现）>

## 文档导航
<指向 CLAUDE.md / docs/STATUS.md / docs/03-design/architecture.md / docs/02-decisions/ / spec>

## License
MIT
```

> **诚实原则**：README 此时**必须**明确标注"骨架阶段，业务功能尚未实现"。README 声称了未实现的功能，是 spec §9 闸门 4 针对的那类不一致。**尤其不要写"M9 已拆分为三个服务"**——M9 是计划，不是现状。

- [ ] **Step 7: 提交**

**本任务拆 3 个提交**（测试 / CI / README 三件不相关的事，回滚时机各不相同）：

```bash
# 提交①：架构测试
git add app/src/test
git commit -F - <<'EOF'
test(build): 新增 ArchitectureTest 强制三域边界

【为什么】
spec §4.2 要求模块边界"不靠自觉"。本次架构的核心价值就是那条域边界
（forum/**、trade/**、seckill/** 两两互斥）——没有它，"三个服务"只是
文档里的三行标题，M9 物理拆分时会发现代码早已缠在一起、拆分收益归零。
但一个从未失败过的测试不是测试而是装饰，因此额外用临时违规类
（trade-order → forum-user）验证了规则真的能拦住跨域依赖，验证后删除。
规则要趁模块还空着落地：空模块基线是绿的，此后任何跨域依赖都是新增的。

【改了什么】
- app/src/test/.../ArchitectureTest.java：4 条核心规则
  （common 不依赖域 / 三个域两两互斥 / domain 不依赖 infra / infra 不依赖域）
  + 1 条可选规则（唯一启动类）
- 违规实验：临时加 trade-order → forum-user 的跨域依赖 → 测试 FAIL
- 同域对照：同域内依赖 → 测试 PASS（证明规则拦的是"跨域"而非"任何依赖"）
- 删除临时类后复跑 → 回到基线绿

【验证】
- 基线：`mvn -pl app -am test -Dtest=ArchitectureTest` → all passed
- 反面：临时加入 trade→forum 依赖 → FAIL，报错指名 TempViolation
- 恢复：删除临时类后复跑 → all passed

【关联】
Refs: spec §4.2、§9 闸门 2、architecture.md §3.1
EOF

# 提交②：CI
git add .github/
git commit -F - <<'EOF'
ci(build): 新增 CI 流水线（编译 → 单测 → 两道闸门脚本）

【为什么】
本机无 Docker（ADR-0004），集成测试跑不了，CI 能提供的就是"编译与
单元测试在干净环境里是否通过"这一层保障；而闸门 3/6 两个脚本是
"依赖真实可解析"与"文档没漂移"的唯一自动化手段，只挂本地等于
只在记得跑的时候才生效。

【改了什么】
- .github/workflows/ci.yml：push/PR 到 main 触发，四步——
  mvn clean compile → mvn test（含 ArchitectureTest）→ check-deps.sh → check-doc-refs.sh
- 集成测试不在 CI 中运行（ADR-0007），此约定在 M1 后由 @Tag("integration") 落实

【验证】
- 本地等价命令逐条跑过：`mvn -B -q clean compile` → BUILD SUCCESS
- `bash tools/check-deps.sh` → 退出码 0
- 真实 CI 运行结果在 Task 9 Step 5 验证

【关联】
Refs: ADR-0007、spec §9 闸门 3/6、§10.8
EOF

# 提交③：README
git add README.md
git commit -F - <<'EOF'
docs(readme): 新增仓库门面 README

【为什么】
README 是面试官打开仓库的第一屏，也是"干净环境按 README 能跑起来"
（M10 闸门）的载体。现在写它的理由是：本地运行命令（`-pl app`）
依赖新的模块结构，早写早暴露"文档里的跑法跟实际结构对不对得上"。

【改了什么】
- README.md：简介、技术栈、架构（三域 + 逻辑态/物理态区分）、
  本地运行（`mvn -pl app -am spring-boot:run`）、项目状态、文档导航
- 如实标注 M0 骨架阶段、业务功能未实现；不写任何未实现的功能

【验证】
- 本地运行步骤中的启动命令已在 Task 1 Step 6 实测通过
- 技术栈表格与 CLAUDE.md 的技术栈白名单逐行核对一致

【关联】
Refs: spec §6.2 M10 闸门、ADR-0006
EOF
```

---

## Task 8: 本地中间件 ES 8 + Kafka 3.9 安装与验证

**Files:**
- Create: `docs/06-runbook/local-setup.md`
- Create: `docs/06-runbook/troubleshooting.md`
- Create: `scripts/` 目录下的启停脚本（`start-es.bat` / `start-kafka.bat`）

**Interfaces:**
- Consumes: 无（环境准备）
- Produces: 运行中的 ES 8（`localhost:9200`）与 Kafka 3.9（`localhost:9092`）；`local-setup.md` 被 README 与后续里程碑引用

**风险与降级路径（重要）**：ES/Kafka 在 Win10 的安装可能遇到坑。**卡住超过 1 天即降级**：ES 改用 7.17.x（仍被 Spring Boot 3.5 支持）。降级需记录到 ADR-0001 的修订中。

- [ ] **Step 0: 向用户确认**

① **这步做什么**：在本机 D 盘新装 ES 8.18.3（含 IK 分词器）+ Kafka 3.9.1（KRaft 模式，无 ZooKeeper），并落地环境文档与启停脚本。**不覆盖**已有的 ES 6.8 与 Kafka 2.8（旧项目可能还要用）。

② **为什么现在做**：M3/M4/M5 三个亮点（多级缓存、Kafka 异步写、ES 检索）**都要连真实中间件**，而本机现有的版本与 ADR-0001 的技术栈基线不匹配（ES 6.8 的 TransportClient 在 ES 8 已彻底移除，Kafka 2.8 还带 ZooKeeper）。本机无 Docker（ADR-0004），**不能等用的时候再"临时拉一个"**——中间件必须实装。这也是 M0 的闸门之一（spec §6.2：ES/Kafka 启动验证 `[REAL]`）。

③ **做法与建议**：
- **方案 A（建议）**：原生装到 D 盘（`D:\aaaSoftware\`），各压 1G 堆。代价：多占约 2.7G 内存（M9 全开时），需要"按需启动"纪律；且 Win10 上 ES 有踩坑可能（已备降级路径：卡住超 1 天改用 ES 7.17.x）。
- 方案 B：只在需要时才装（推迟到 M3/M5）。代价：M0 闸门"ES/Kafka 启动验证 `[REAL]`"过不了，且安装风险被推迟到里程碑中段，一旦卡住会连带阻塞亮点开发。
- 方案 C：用 Docker Compose 拉起。**本机没有 Docker、Win10 Home 无 WSL2**（ADR-0004），不可行。

**建议 A，等用户回应后再进 Step 1。**

- [ ] **Step 1: 确认安装位置与端口占用**

```bash
ls -d /d/aaaSoftware/elasticsearch-* /d/aaaSoftware/kafka* 2>/dev/null
netstat -ano | grep LISTENING | grep -E ':(9200|9300|9092|9093) '
df -h /c /d | head -5
```
Expected: 看到已有的 `elasticsearch-6.8.23` 与 `kafka` 目录；**9200/9092 端口必须空闲**（旧版未运行）；D 盘可用空间 > 5G。

- [ ] **Step 2: 下载并解压 ES 8 到 D 盘**

```bash
cd /d/aaaSoftware/_downloads
curl -LO "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-8.18.3-windows-x86_64.zip"
unzip -q elasticsearch-8.18.3-windows-x86_64.zip -d /d/aaaSoftware/
```
Expected: `D:\aaaSoftware\elasticsearch-8.18.3\` 存在。

> **不要覆盖** 已有的 `elasticsearch-6.8.23`——旧项目可能仍需要它。

- [ ] **Step 3: 关闭 ES 安全配置（仅本地开发）**

编辑 `D:\aaaSoftware\elasticsearch-8.18.3\config\elasticsearch.yml`，追加：

```yaml
# ---- 本地开发配置（生产环境必须开启安全，见 docs/06-runbook/local-setup.md）----
cluster.name: wingtisky-forum
node.name: node-1
discovery.type: single-node
xpack.security.enabled: false
xpack.security.enrollment.enabled: false
http.port: 9200
network.host: 127.0.0.1
```

编辑 `config\jvm.options`，把堆内存压到本机可承受：

```
-Xms1g
-Xmx1g
```

> **面试要点**：`xpack.security.enabled: false` **只允许出现在本地开发**。`local-setup.md` 里必须写清生产环境如何开启（TLS + 内置用户 + API Key），否则这是简历上的减分项而非加分项。

- [ ] **Step 4: 安装 IK 分词器**

```bash
cd /d/aaaSoftware/elasticsearch-8.18.3/bin
./elasticsearch-plugin install https://get.infini.cloud/elasticsearch/analysis-ik/8.18.3
```
Expected: 提示 `Installed analysis-ik`。
**若该 URL 404**：去 https://github.com/infinilabs/analysis-ik/releases 找到与 ES 8.18.3 匹配的版本，下载 zip 后手动解压到 `plugins/ik/`。**不要把地址猜错就跳过**——IK 是亮点 3 的必要组件。

- [ ] **Step 5: 启动 ES 并验证**

```bash
cd /d/aaaSoftware/elasticsearch-8.18.3/bin && ./elasticsearch.bat &
sleep 60
curl -s http://localhost:9200
curl -s "http://localhost:9200/_analyze" -H 'Content-Type: application/json' -d '{"analyzer":"ik_max_word","text":"多级缓存与Redisson分布式锁"}'
```
Expected:
- 第一条：返回含 `"cluster_name" : "wingtisky-forum"` 的 JSON
- 第二条：返回分词结果，**能看出"多级缓存""分布式锁"被正确切分**（若切碎成单字，说明 IK 未装好）

- [ ] **Step 6: 下载并配置 Kafka 3.9（KRaft 模式）**

```bash
cd /d/aaaSoftware/_downloads
curl -LO "https://archive.apache.org/dist/kafka/3.9.1/kafka_2.13-3.9.1.tgz"
mkdir -p /d/aaaSoftware/kafka-3.9.1
tar -xzf kafka_2.13-3.9.1.tgz -C /d/aaaSoftware/kafka-3.9.1 --strip-components=1
```

> 版本号以 Apache 归档实际存在的为准；下载前先确认该路径可达，不要凭记忆写死。

生成 KRaft 集群 ID 并格式化存储目录：

```bash
cd /d/aaaSoftware/kafka-3.9.1
KAFKA_CLUSTER_ID="$(bin/windows/kafka-storage.bat random-uuid)"
echo "生成的集群 ID: $KAFKA_CLUSTER_ID"
bin/windows/kafka-storage.bat format -t "$KAFKA_CLUSTER_ID" -c config/kraft/server.properties
```
Expected: 提示 `Formatting metadata directory` 且成功。

编辑 `config/kraft/server.properties`，确认/修改：

```properties
log.dirs=D:/aaaSoftware/kafka-3.9.1/kraft-logs
listeners=PLAINTEXT://127.0.0.1:9092,CONTROLLER://127.0.0.1:9093
advertised.listeners=PLAINTEXT://127.0.0.1:9092
num.partitions=3
```

- [ ] **Step 7: 启动 Kafka 并验证（KRaft，无 ZooKeeper）**

```bash
cd /d/aaaSoftware/kafka-3.9.1
bin/windows/kafka-server-start.bat config/kraft/server.properties &
sleep 30
bin/windows/kafka-topics.bat --bootstrap-server 127.0.0.1:9092 --create --topic smoke-test --partitions 3 --replication-factor 1
bin/windows/kafka-topics.bat --bootstrap-server 127.0.0.1:9092 --describe --topic smoke-test
```
Expected: 创建成功；`describe` 输出显示 3 个分区、`Leader` 均非 `none`。

**关键验证点**：确认进程列表里**没有 ZooKeeper**——这是 KRaft 的核心收益，也是面试可讲的点。

```bash
tasklist | grep -iE 'java|zookeeper' | head
```
Expected: 有 java 进程（Kafka broker），**无 ZooKeeper**。

- [ ] **Step 8: 验证 Redisson 与 Redis 5 的兼容性（风险项）**

这是 spec 未预见但本次查版本时发现的风险：**Redisson 3.52.0 可能使用 Redis 5.0.14.1 不支持的命令**。

先确认 Redis 在跑，然后用一个最小清单验证：

```bash
cd /d/aaaSoftware/redis && ./redis-server.exe &
sleep 3
./redis-cli.exe INFO server | grep redis_version
```

Expected: 输出版本号。**若 Redisson 在 M3 出现 `ERR unknown command`，降级 Redisson 到与 Redis 5 兼容的版本（逐个版本试 3.2x 一线），并把结论写入 ADR-0001 的修订。** 本步只需确认 Redis 可用并记录版本，兼容性结论在 M3 验证。

- [ ] **Step 9: 写 `docs/06-runbook/local-setup.md`**

必须包含的章节：

```
# 本地环境搭建
## 1. 前置要求（JDK 17 / Maven / Node 版本与路径）
## 2. 中间件清单与端口表
## 3. MySQL 8.0（沿用已有）
## 4. Redis 5.0.14.1（沿用已有）
## 5. Kafka 3.9.1 KRaft（本次新装，含集群 ID 生成与格式化步骤）
## 6. Elasticsearch 8.18.3 + IK 分词器（本次新装）
## 7. 一键启停（scripts/ 下的脚本说明）
## 8. 磁盘与内存注意事项（C 盘仅剩 ~19G，安装一律 D 盘）
## 9. 生产环境差异说明 ★ —— ES 安全配置、Kafka 副本数、
##    Redis 密码等在生产必须如何设置（这段是面试可讲的）
```

- [ ] **Step 10: 写 `docs/06-runbook/troubleshooting.md` 并记录本次踩的坑**

初始内容为本次安装实际遇到的问题。**若一切顺利，也必须写一条**：

```markdown
# 踩坑记录

> 用途有二：① 防止后续反复踩同一个坑；② 面试时"我遇到过什么问题、
> 怎么定位、怎么解决"是最有说服力的素材。**只记真实的坑，不要编。**

## 2026-09-13 · ES 8 首次启动

**现象**：<如实填写>
**原因**：<如实填写>
**解决**：<如实填写>
**耗时**：<如实填写>
```

- [ ] **Step 11: 提交**

**本任务拆 3 个提交**，因为三块的**回滚代价完全不同**：ES 与 Kafka 是两个彼此独立的中间件（ES 装歪了不该连累 Kafka 的文档）；`local-setup.md` 与 `troubleshooting.md` 是文档（改文档不该动脚本）；`scripts/` 是启停脚本（改脚本不该动文档）。

> **先写脚本、真的重启一次、再提交**：Step 6/7 里启动 ES/Kafka 用的是 `bin/*.bat` 原生命令，脚本此刻还不存在。**脚本写完后必须停掉进程、用脚本重新拉起一遍**，才能让下面两条提交的 `【验证】` 是真实执行过的（spec §9 闸门 4 要求 `【验证】` 附实际命令与输出，不接受"应该没问题"）。顺带也验证了脚本本身能跑通——这就是"提交前逐条对照"要抓的东西。

```bash
# 提交①：ES 相关的环境准备（ES 本体是外部安装，纳入版本控制的是脚本与文档）
git add scripts/start-es.bat
git commit -F - <<'EOF'
chore(runbook): 新增 ES 8.18.3 启动脚本

【为什么】
本机无 Docker（ADR-0004），中间件靠原生进程管理，启停命令散在文档里
每次都要重新拼。把启动参数（含 1G 堆限制）固化进脚本，是保证"按需
启动"纪律（architecture.md §6.2）可执行的前提——随手 `elasticsearch.bat`
会按默认堆吃满内存。

【改了什么】
- scripts/start-es.bat：以 1G 堆启动 D:\aaaSoftware\elasticsearch-8.18.3
  （ES 本体的安装与 elasticsearch.yml / jvm.options 的修改不入版本控制，
  因为它们是机器本地状态；改动项记录在 docs/06-runbook/local-setup.md）

【验证】
- 执行脚本后 `curl -s http://localhost:9200` → cluster_name = wingtisky-forum
- `curl -s "localhost:9200/_analyze" -d '{"analyzer":"ik_max_word","text":"多级缓存与Redisson分布式锁"}'`
  → 分词正确（未切成单字，说明 IK 已装好）

【关联】
Refs: ADR-0001、ADR-0004、architecture.md §6.2
EOF

# 提交②：Kafka KRaft 启动脚本
git add scripts/start-kafka.bat
git commit -F - <<'EOF'
chore(runbook): 新增 Kafka 3.9.1 (KRaft) 启动脚本

【为什么】
KRaft 模式去掉了 ZooKeeper，但启动前需要集群 ID 与格式化过的存储目录
（只需一次）。这两步容易漏、漏了报错信息又不直观，因此固化成脚本，
既省事也把"没有 ZK 进程"这个事实写进流程里。

【改了什么】
- scripts/start-kafka.bat：以 config/kraft/server.properties 启动
  D:\aaaSoftware\kafka-3.9.1（集群 ID 生成与 format 已完成，属一次性步骤，
  记录在 docs/06-runbook/local-setup.md）

【验证】
- 启动后 `kafka-topics.bat --bootstrap-server 127.0.0.1:9092 --describe --topic smoke-test`
  → 3 分区，Leader 均非 none
- `tasklist | grep -i zookeeper` → 无输出（KRaft 生效）

【关联】
Refs: ADR-0001、spec §3
EOF

# 提交③：环境文档（安装步骤 + 踩坑记录）
git add docs/06-runbook/
git commit -F - <<'EOF'
docs(runbook): 新增本地环境搭建与踩坑记录

【为什么】
"干净环境按 README 能跑起来"（M10 闸门）的前提是中间件装法有据可查；
而面试时最有说服力的素材是"我遇到过什么问题、怎么定位、怎么解决"——
那只能靠当时真实的记录，事后补不出来。两份文档分工：local-setup 给
接手者照着装，troubleshooting 留给复盘与讲述。

【改了什么】
- docs/06-runbook/local-setup.md：九节——前置要求、中间件与端口表、
  MySQL / Redis（沿用）、Kafka 3.9.1 KRaft（含集群 ID 生成与格式化）、
  ES 8.18.3 + IK、一键启停、磁盘内存注意、**生产环境差异说明**（ES 安全
  配置、Kafka 副本数、Redis 密码在生产必须如何设置）
- docs/06-runbook/troubleshooting.md：本次实际踩的坑（如实记录，未遇到
  也写明"无"——不编造）

【验证】
- 按 local-setup.md 的步骤在一台干净会话中复现过（命令见 Task 8 Step 2–8）
- 生产环境差异一节的每一条都与本地配置逐项对照过（本地关了什么、
  生产该开什么）

【关联】
Refs: ADR-0001、ADR-0004、spec §3、§6.2 M0、architecture.md §6.2
EOF
```

---

## Task 9: GitHub 仓库创建与首次推送

**Files:**
- Modify: git remote 配置
- Create: GitHub 上的 public 仓库 `wingtisky-forum`

**Interfaces:**
- Consumes: Task 1-8 的全部产物
- Produces: 远程 `origin`；CI 首次运行结果

- [ ] **Step 0: 向用户确认**

① **这步做什么**：在 GitHub 上建 public 仓库 `wingtisky-forum`、推送 `main`、配置 main 分支保护、确认 CI 绿。

② **为什么现在做**：spec §1.5 与 ADR-0006 把"GitHub 公开"定为交付形态——**它是简历项目的一部分，不是可选项**。而 CI 只有推到远端才会真正运行：Task 7 写的 `ci.yml` 此前**从未在干净环境里执行过**，本地能过不等于 CI 能过（Linux runner 上没有 `D:` 盘、没有本机中间件、`tools/*.sh` 的行尾与执行权限都不同）。这是 M0 唯一的"跨环境"验证机会。

③ **做法与建议**：
- **方案 A（建议）**：`gh repo create --public`，先推 `main`，再按需配置分支保护。代价：**公开即不可撤回地暴露全部历史**——所以 Step 1 的敏感信息扫描不可跳过（推送后再撤回要改密码 + `git filter-repo` + force push，成本高一个数量级）。
- 方案 B：先建 private，稳定后再转 public。代价：分支保护与 CI 行为在 private 下与 public 不同（部分规则免费账号受限），切换后要重验一遍，等于把同一步做两次。
- 方案 C：不建远端，M0 只留本地仓库。代价：ADR-0006 的交付形态落空，CI 从未运行过，且 M10 的"干净环境按 README 能跑起来"失去了参照。

**建议 A，等用户回应后再进 Step 1。** 若用户对"公开"有顾虑（例如提交邮箱不是 GitHub noreply），**先解决邮箱再推**——已推送的作者信息很难改。

- [ ] **Step 1: 推送前自查（防敏感信息泄露）**

```bash
cd /d/aaaDocuments/project/WingtiskyForum
git log --oneline
echo "=== 全历史文件清单 ==="
git log --all --pretty=format: --name-only | sort -u | grep -v '^$'
echo "=== 敏感信息扫描 ==="
grep -rniE '(password|secret|token|apikey|access[_-]?key)\s*[:=]\s*["'"'"'][^"'"'"']{6,}' \
  --include='*.java' --include='*.yml' --include='*.yaml' \
  --include='*.properties' --include='*.xml' --include='*.md' . \
  | grep -v '\.git/' | grep -viE '(placeholder|example|your_|xxx|<|\$\{)' || echo "未发现硬编码密钥"
```
Expected: 文件清单中**不应有** `.env`、`target/`、`.idea/`、任何本地配置；敏感信息扫描无命中。

**这一步不可跳过**：推到公开仓库后再撤回，成本高一个数量级（需改密码 + `git filter-repo` + force push）。

- [ ] **Step 2: 创建 GitHub 仓库**

```bash
gh repo create wingtisky-forum --public --source=. --remote=origin \
  --description "技术社区 + 付费内容 · Java 17 + Spring Boot 3 · 三域逻辑态（forum/trade/seckill）· 多级缓存 / Kafka 异步 / ES 检索 / 深翻页治理 / RBAC + 滑动窗口限流 / 秒杀一致性"
```
Expected: 输出仓库 URL `https://github.com/Wingtisky20/wingtisky-forum`。

> **描述按现状写，不按计划写**：此刻仓库里只有骨架，但仓库描述是**目标定位**而非进度声明，写"三域逻辑态"是准确的（模块结构已按三个域划好）。**不要写"已拆分为三个服务"**——那是 M9 的事（README 里同理，见 Task 7 Step 6 的诚实原则）。

> 确认仓库为 **public**。若误建为 private，用 `gh repo edit --visibility public --accept-visibility-change-consequences` 修正。

- [ ] **Step 3: 推送 main**

```bash
git push -u origin main
git remote -v
```
Expected: 推送成功；`origin` 同时有 fetch 与 push 地址。

- [ ] **Step 4: 配置仓库规则（保护 main）**

```bash
gh api -X PUT "repos/Wingtisky20/wingtisky-forum/branches/main/protection" \
  -f "required_status_checks[strict]=true" \
  -F "required_status_checks[contexts][]=build" \
  -F "enforce_admins=false" \
  -F "required_pull_request_reviews[required_approving_review_count]=0" \
  -F "restrictions=" 2>&1 | head -20
```
Expected: 返回 JSON 含 `"url"`。若 API 因免费账号限制报错，**记录到 troubleshooting.md 并继续**——规则是锦上添花，不是阻塞项。

- [ ] **Step 5: 验证 CI 通过**

```bash
sleep 60
gh run list --limit 3
gh run watch --exit-status || gh run view --log-failed | tail -40
```
Expected: 最近一次 run 状态为 `completed / success`。
**若失败**：按日志修复后走 feature 分支 + PR（不要直接 push main 绕过）。

- [ ] **Step 6: 提交（仅当 Step 4/5 产生了修复时）**

本任务**本身不产生提交**——它推的是 Task 1–8 已有的提交，仓库内容一行没改。只有 Step 4/5 真的暴露出问题时才有提交，且**每个修复独立成条**（spec §10.4.1：修复永不混进功能提交）。

```bash
cd /d/aaaDocuments/project/WingtiskyForum
git checkout -b fix/ci-<问题短名>

git add <修复涉及的文件>
git commit -F - <<'EOF'
fix(ci): <一句话描述 CI 在 Linux runner 上暴露的问题>

【为什么】
Task 7 的 ci.yml 此前只在 Windows 本地间接验证过，从未在 GitHub 的
Linux runner 上真正跑过。本次首推暴露了该问题——本地能过不等于 CI 能过
（无 D: 盘、无本机中间件、tools/*.sh 的行尾与执行权限不同）。

【改了什么】
- <逐项列出实际改动>

【验证】
- `gh run view --log-failed` 的报错原文：<粘贴>
- 修复后 `gh run list --limit 1` → completed / success

【关联】
Refs: spec §1.5、ADR-0006、Task 7
EOF

git push -u origin fix/ci-<问题短名>
gh pr create --base main --title "fix(ci): <标题>" --body "<问题与修复说明>"
```

> **不要直接 push main 绕过**（Step 5 已强调）。本任务 Step 3 的 `git push -u origin main` 是**仓库首次推送**——那时远端还没有 `main`，不存在"绕过保护"；Step 4 配置分支保护之后，所有改动都必须走 PR。这两件事顺序不能颠倒，否则分支保护就形同虚设。

**若 Step 4/5 一切顺利（大概率）**：本任务零提交，直接进 Task 10。**这不算"任务没完成"**——任务 9 的产物是远端仓库与 CI 绿灯，不是提交。

---

## Task 10: M0 收尾

**Files:**
- Modify: `docs/STATUS.md`
- Create: `docs/04-log/01-M0-项目启动与协作基建.md`
- Create: git tag `m0-done`

**Interfaces:**
- Consumes: Task 1-9 的全部产物
- Produces: `m0-done` tag；更新后的 STATUS.md（M1 的起点）；`docs/04-log/` 的第一份执行记录

- [ ] **Step 0: 向用户确认**

① **这步做什么**：逐项核对 M0 闸门、实测"新对话能否接手"、更新 STATUS.md、写 `docs/04-log/` 的第一份执行记录、打 `m0-done` tag、发 Release。

② **为什么现在做**：spec §6.2 的 M0 闸门是"不达标不算完成"的硬判据，而闸门 1（证据制）要求结论附真实输出——**不逐项核对就打 tag，等于把 tag 变成一句口号**。"新对话能否接手"更是 M0 交付物的最终检验：前面 9 个任务建的所有文档，只有在一个零上下文的新对话里真能读懂，才算数。

③ **做法与建议**：
- **方案 A（建议）**：八项闸门逐条跑、留真实输出；执行记录**精炼**（按 spec §8.6 的七节结构，宁少勿多）。
- 方案 B：只跑编译与测试就打 tag，其余"回头补"。代价：tag 一旦发布就代表"M0 完成"，后续补的证据无法回溯到那个时刻，且 spec §9 闸门 1 当场失守。
- 方案 C：执行记录写全量日志（把每个 `mvn` 输出都贴进去）。**违反 spec §8.6 的"精炼优先"**——这份文档是给人（你和面试官）看的，过程细节留给 commit；500 行以上还要拆。

**建议 A，等用户回应后再进 Step 1。**

- [ ] **Step 1: 逐项核对 M0 闸门（spec §6.2）**

```bash
echo "=== 1. 编译 ==="; mvn -q clean compile && echo "OK"
echo "=== 2. 架构测试 ==="; mvn -q -pl app -am test -Dtest=ArchitectureTest && echo "OK"
echo "=== 3. 防漂移脚本 ==="; bash tools/check-deps.sh && bash tools/check-doc-refs.sh
echo "=== 4. 提交模板 ==="; git config --get commit.template
echo "=== 5. 模块结构 ==="; grep -c '<module>' pom.xml; ls -d forum/*/ trade/*/ seckill/*/ app
echo "=== 6. 旧模块名已清零 ==="; grep -rlE 'forum-module-|forum-boot' --include='*.md' --include='*.xml' --include='*.yml' --include='*.sh' . | grep -v '\.git/' || echo "无残留"
echo "=== 7. ES ==="; curl -s http://localhost:9200 | head -3
echo "=== 8. Kafka（无 ZK）==="; tasklist | grep -ci zookeeper || echo "0 个 ZK 进程"
echo "=== 9. 远程 ==="; git remote -v
echo "=== 10. CI ==="; gh run list --limit 1
```
Expected:
- 1–4 项输出 `OK` 或脚本退出码 `0`；
- 第 5 项：父 POM 有 **7** 个 `<module>`，且 `forum/forum-user` 等 12 个叶子模块目录全部存在；
- 第 6 项输出 `无残留`——**若命中任何文件，先改干净再继续**（旧模块名残留是本次重写最容易漏的一类缺陷，它会直接让新对话照着错名字干活）；
- 7–10 项：ES 返回 `cluster_name`、无 ZooKeeper 进程、`origin` 存在、CI 最近一次 `success`。

全部符合 spec §6.2 的 M0 闸门要求。**任何一项不达标，M0 不算完成**——这是 spec §9 闸门 1（证据制）的直接应用。

- [ ] **Step 2: 实测"新对话能否接手"（M0 最关键的一次验证）**

**在一个全新对话中**（不携带本次上下文），只输入：

```
读文档，继续
```

观察新对话是否能：① 找到并读取 `CLAUDE.md`、`docs/STATUS.md`、`collaboration.md`；② 正确复述当前阶段与下一步；③ 不提出与既有决策冲突的建议。

**③的判据要具体**：新对话**不应**建议引入 Docker、建议改成全套微服务（Nacos+Sentinel+Seata）、建议把 `forum-service` 现在就拆成进程、或把模块名写回 `forum-module-*`。**若出现上述任一**→ **说明 CLAUDE.md 的约束写得不够醒目，必须回头加固**。这是本次 M0 交付物最重要的质量检验，不可省略。

- [ ] **Step 3: 更新 `docs/STATUS.md`**

把所有 M0 任务的状态改为「完成」，证据等级按实际情况填写（真实链路跑过的填 `[REAL]`），并更新：
- 顶部"最后更新"日期
- "当前阶段" → 准备进入 M1
- "下一步" → M1 的第一步
- "待核实" → 填入尚未解决的事项（如 Redisson 3.52.0 / Redis 5 兼容性待 M3 验证）；**M0 工期 1.5 周是预估值，按实际耗时校准并记录**

- [ ] **Step 4: 写 `docs/04-log/01-M0-项目启动与协作基建.md`**

**这是执行记录，不是工作日志。** 按 spec §8.6 的七节结构写，**精炼优先、宁少勿多**——只留"决策 + 为什么 + 怎么验证"，过程细节留给 commit（每个 `mvn` 的输出都在提交信息里，不必重抄）。**单份超约 500 行即拆**。

```markdown
# 01 · M0 项目启动与协作基建

> 上接：无（本文件是系列第一份）

## 一句话总结
<一句话说清 M0 交付了什么>

## 产出了什么
<按类列：骨架 / 协作机制 / ADR / 工具 / 环境 / 远端。指向对应目录或 tag，不贴文件内容>

## 关键决策与理由
<指向本阶段涉及的 ADR：0001（修订）、0002（修订）、0005（废弃）、0009、0010、0011，
 各一句话说清"决定了什么、为什么"；不重述 ADR 正文>

## 你可以怎么验证
<给用户/面试官可直接复制执行的命令与预期输出：mvn clean compile、
 mvn -pl app -am test -Dtest=ArchitectureTest、两个 tools 脚本、
 curl localhost:9200、kafka-topics --describe>
<这是本文件最值钱的一节——它让"我做到了"变成"你可以自己确认">

## 踩过的坑
<如实填写。没有就写"无"——不要编>

## 遗留与风险
<如实填写，含未解决的>

## 回滚记录
<本次有无回滚；无则写"无">
```

- [ ] **Step 5: 打 tag 并推送**

**拆 2 个提交**：状态源更新与执行记录是两件事（前者是机器/会话读的，后者是给人看的），且回滚时机不同。

```bash
git add docs/STATUS.md
git commit -F - <<'EOF'
docs(status): M0 完成，状态源推进至 M1

【为什么】
M0 的全部闸门已逐项验证通过，需要把状态沉淀回唯一状态源，
使新对话可以从 M1 直接接手——STATUS.md 的"当前上下文文件"字段
是会话协议读的第一跳，它停在 M0 会让接手者从错误的起点开始。

【改了什么】
- docs/STATUS.md：M0 全部任务标记完成并填写证据等级；
  当前阶段推进至 M1；补充"待核实"（Redisson 3.52.0 与 Redis 5 的兼容性）
  与 M0 工期实测校准结果

【验证】
- M0 闸门逐项核对命令与输出见本提交 diff 中的证据表
- 新对话"读文档，继续"实测可正确接手（无决策冲突）

【关联】
Refs: spec §6.2 M0 闸门、§9 闸门 1
EOF

git add docs/04-log/
git commit -F - <<'EOF'
docs(log): 新增 M0 执行记录

【为什么】
spec §8.6 用 docs/04-log/ 取代了旧的按日期 journal 方案——两者都在记
"发生了什么"，并存必然打架。执行记录与 commit 分工不同：commit 答
"每一步改了什么"，执行记录答"这一阶段为什么这么做、用户怎么自己验证"。
M0 是所有后续阶段的参照点，这份记录必须精炼（宁少勿多），而不是
把 10 个任务的 mvn 输出重抄一遍。

【改了什么】
- docs/04-log/01-M0-项目启动与协作基建.md：按 spec §8.6 的七节结构
  （一句话总结 / 产出了什么 / 关键决策与理由 / 你可以怎么验证 /
  踩过的坑 / 遗留与风险 / 回滚记录）
- 「你可以怎么验证」一节给出可直接复制的命令与预期输出

【验证】
- 副本中的每条验证命令均已实际执行过（见对应 commit 的【验证】节）
- 文件行数 < 500（spec §8.6 的拆分阈值）

【关联】
Refs: spec §8.6
EOF

git tag -a m0-done -m "M0 项目启动与协作基建完成：三域 12 模块骨架 / 协作文档 / 11 条 ADR / 防漂移脚本 / ArchUnit 边界测试 / CI / ES8+Kafka3.9 / GitHub 公开仓库"
git push origin main --follow-tags
```

- [ ] **Step 6: 发 GitHub Release**

```bash
gh release create m0-done --title "M0 · 项目启动与协作基建" --notes-file docs/04-log/01-M0-项目启动与协作基建.md
```
Expected: Release 创建成功，可在仓库页面看到。

- [ ] **Step 7: 向用户报告**

按 spec §8.2 的收场要求，向用户如实报告：真实改了什么、验证到什么程度（附证据）、遗留什么问题、下一个里程碑是什么。

---

## Self-Review

**1. Spec coverage** — 逐节核对 spec，确认有任务覆盖：

| spec 章节 | 覆盖任务 |
|---|---|
| §1.3 / §1.5 亮点与明确不做 | Task 3（写入 CLAUDE.md）、Task 4（写入 charter.md §6） |
| §3 技术栈基线 | Task 1（POM 版本值）、Task 8（中间件）、ADR-0001 |
| §4.1 模块划分（**已变更**） | **Task 1 —— 以 `architecture.md` §3.1 逻辑态为权威，非 spec §4.1 的旧 9 模块** |
| §4.2 边界规则（ArchUnit） | Task 7（三域两两互斥 + common/domain/infra 三条） |
| §4.3 机制 vs 策略 | Task 1（package-info 中声明边界）、Task 3（写入 CLAUDE.md） |
| §6.2 M0 闸门 | Task 10 Step 1（**含新增的"旧模块名已清零"一项**） |
| §6.2 M0 任务内容（仓库/骨架/CLAUDE/STATUS/ADR/CI/中间件/目录/术语表） | Task 1–9 全覆盖 |
| §6.2 M0 工期 1.5 周 | 计划头部「工期」字段；Task 10 Step 3 校准 |
| §8.1 目录结构（**已变更**） | Task 1（三域 12 模块）、Task 3、4、5、6、7、8；**spec §8.1 的目录树是旧结构，以架构文档为准** |
| §8.2 会话协议 | Task 3（写入 CLAUDE.md）、Task 4（写入 collaboration.md）、Task 10 Step 2（实测） |
| §8.3 三条铁律 | Task 3、Task 4；**变更留痕的实例见 Task 5 的 0002/0005 状态改写** |
| §8.5 用户参与机制 | **每个任务的 Step 0**（T1–T10 共 10 处）、Task 3（写入 CLAUDE.md）、Task 4（collaboration.md §10） |
| §8.6 执行记录 `docs/04-log/` | Task 10 Step 4（产出 `01-M0-项目启动与协作基建.md`）、Task 3（写入 CLAUDE.md 收场流程）、Task 4（collaboration.md §11） |
| §9 闸门 1（证据制） | Task 4（STATUS.md 图例）、Task 10 Step 1 |
| §9 闸门 2（反验证剧场） | Task 7 Step 3（ArchUnit 反面对照：违规红 + 同域绿） |
| §9 闸门 3（依赖防幻觉） | Task 1 Step 1/Step 3（版本核查）、Task 6 |
| §9 闸门 4（提交 diff 对账） | Task 2（模板）、Task 1 Step 7、所有任务的提交步骤 |
| §9 闸门 5（高危区点名） | Task 3（写入 CLAUDE.md） |
| §9 闸门 6（文档-代码对账） | Task 6（`SRC_DIRS` 按 12 模块更新） |
| §10.1 仓库与身份 | 前置已完成（git init + 身份配置） |
| §10.2 分支模型 | Task 9 Step 4（分支保护）、Task 9 Step 5（走 PR） |
| §10.3 提交规范 | Task 2 |
| §10.4 提交粒度 | Task 2、所有提交步骤；**Task 1（4 提交）、Task 6（2 提交）、Task 7（3 提交）、Task 10（2 提交）为显式拆分** |
| §10.4.1 提交频率与拆分 | 计划头部 Global Constraints；**每个任务末尾的拆分说明** |
| §10.5 回滚规范 | Task 4（写入 collaboration.md） |
| §10.6 追溯命令 | Task 4（写入 collaboration.md） |
| §10.7 Tag 与 Release | Task 10 |
| §10.8 CI | Task 7 |
| §10.9 敏感信息 | Task 9 Step 1 |
| §11 风险表 | Task 8（ES 降级路径）、Task 10 Step 2（可读性验证） |
| architecture.md §2.2 故障域隔离 | Task 1 Step 4（交易/秒杀分域的提交理由） |
| architecture.md §3.1 逻辑态 / §3.2 物理态 | Task 1（12 模块）、Task 3（CLAUDE.md 注明 M9 才建三个 app）、Task 7 Step 6（README 不写未实现的事） |
| architecture.md §4.2 明确不引入 | Task 3（CLAUDE.md 禁止项改写） |
| ADR-0009 / 0010 / 0011（已存在） | Task 3（CLAUDE.md 引用）、Task 5（索引列为"已存在"） |

**未覆盖需说明**：
- `docs/01-requirements/prd.md`、`domain-model.md`、`docs/05-interview/*`、`docs/assets/*` 属后续里程碑（M1 起）产出，本计划（M0）不涉及。
- `docs/03-design/architecture.md` **已存在**（本次架构变更的权威文档），M0 **不重写它**——本计划只在引用它的结论。
- M9 的物理拆分（`forum-app`/`trade-app`/`seckill-app`/`gateway`）与拆分对照实验**明确不属于 M0**，本计划只保证边界已画好、M9 时业务模块一行不改。

**2. Placeholder scan** — 已检查：无 TBD / TODO / "实现细节待定" / "类似 Task N"。所有代码步骤含完整内容；所有验证步骤含具体命令与预期输出。Task 5 的 ADR-0003~0008 采用"精确论点清单 + 来源映射"而非全文，理由见本计划开头的"文档书写约定"——这些 ADR 的论点已由 spec §2 完全确定，属提炼而非创作。

**3. Type consistency** — 已核对：
- **12 个叶子模块的 artifactId 在 Task 1（两处依赖表与 POM）、Task 1 Step 5（包名映射表）、Task 6（`SRC_DIRS`）、Task 7（ArchUnit 包名）中逐字一致**；三个域聚合模块 `forum`/`trade`/`seckill` 只在 Task 1 与目录结构中出现，不进 `dependencyManagement`
- 包名在 Task 1 Step 5 的映射表、Task 3（CLAUDE.md 模块边界块）、Task 6（`SRC_DIRS` 路径）、Task 7（`DOMAINS` 数组）中一致：`com.wingtisky.forum` + `{common,domain,infra}` / `{forum,trade,seckill}.{模块名}`
- 父 POM `<modules>` 的 7 项（三共享 + 三域聚合 + `app`）在 Task 1 Step 2/3/4/5 的逐刀追加中**顺序一致、单调增长**
- 启动类全限定名 `com.wingtisky.forum.WingtiskyForumApplication` 在 Task 1 Step 5、Task 10 Step 1（日志断言）中一致；**模块路径为 `app/`**
- 脚本路径 `tools/check-deps.sh`、`tools/check-doc-refs.sh` 在 Task 6、7（CI）中一致
- 状态文件路径 `docs/STATUS.md` 在 Task 4、10 与 CLAUDE.md 中一致；**执行记录路径 `docs/04-log/01-M0-项目启动与协作基建.md` 在 Task 10（创建、Release notes）与 CLAUDE.md（收场第 2 条）中一致，全文无 `04-journal` 残留**
- 版本变量 `redisson.version` / `caffeine.version` / `archunit.version` 在 Task 1 Step 2 定义并被引用；**具体值 3.52.0 / 3.2.4 / 1.5.0 在计划头部、Task 1、Task 3（CLAUDE.md）、Task 5（ADR-0001 修订记录）中一致**
- M0 工期 `1.5 周` 在计划头部（Goal 段）与 spec §6.2 一致

**4. 刻意保留的空白**（非疏漏）：
- Task 8 Step 6 的 Kafka 下载 URL 标注"以 Apache 归档实际存在的为准"——因为凭记忆写死版本 URL 正是闸门 3 要防的行为
- Task 8 Step 10 的 troubleshooting 内容留待执行时如实填写——编造踩坑经历违反诚实原则
- Task 7 Step 1 的规则 5（唯一启动类）标注为**可选**，并说明省略时须在提交信息中说明理由——因为它的 `importPackages` 范围在 M9 必改，属"现在收益低、将来要维护"的一类规则；**但核心的规则 2（三域互斥）不可省**
- 每个任务 Step 0 的"三种做法"是**建议稿**：执行时若发现更合适的选项，可替换为真实选项——**Step 0 的实质是"停下来说清楚并等回应"，不是照抄这三段文字**；但跳过 Step 0 直接开工是违反 spec §8.5 的

**5. 本次重写的自查结论（诚实记录）**：
- 已全文检索：`forum-module-*`、`forum-boot`、`forum-common`、`forum-domain`、`forum-infra`、`04-journal` **均已替换或删除**（Task 1 Step 7、Task 3 Step 2、Task 10 Step 1 三处写成了可执行的自查命令）
- **未能消除的不一致**：`docs/superpowers/specs/2026-09-13-WingtiskyForum-design.md` 的 §4.1 模块划分、§8.1 目录结构、§3 技术栈表（写 Boot 3.x 未写补丁版）仍是旧内容。spec 已冻结为历史快照、**本计划不得修改它**，故此不一致**必然存在**；处理方式是本计划与 `architecture.md` 明确声明权威顺序（实现按 `architecture.md` §3.1），并把"新对话不得照 spec §4.1/§8.1 实现"写进 Task 3 与 Task 4 的注意事项。**这一条是留给执行者的真实风险点，不是可以忽略的细节。**

**6. 重写时发现、但本计划未处理的其他存量矛盾**（如实列出，供后续决策，不在本次改文档的授权范围内）：

| # | 矛盾 | 位置 | 现状 |
|---|---|---|---|
| a | spec §8.2 收场流程第 2 条仍写 `docs/04-journal/YYYY-MM-DD.md`（按日期），与 §8.6 的 `docs/04-log/`（按里程碑）**同在一份 spec 内互相打架** | spec §8.2 vs §8.6 | 本计划取 §8.6 为准（更新、更具体）。spec 内的这处自相矛盾**未被消除** |
| b | spec §6.3 提到旧模块名 `forum-module-search` | spec §6.3 | 本计划已按新结构实现，但 spec 文字未改 |
| c | spec §8.1 目录树含 `frontend/`，字面上像是 M0 该建的 | spec §8.1 | 本计划已明确 M0 **不建** `frontend/`（M2 才建），并在 File Structure 与 Task 1 Step 7 双重声明 |
| d | 原计划 Task 9 首次推送即 `git push -u origin main`，而 spec §10.2 规定不得直接 push main | 原 Task 9 vs spec §10.2 | 本次重写已解释：**首次推送时远端无 `main`，不存在绕过分支保护**；Step 4 配好保护后所有改动走 PR，两件事顺序不可颠倒。属**已解释但未修改 spec** 的一类 |
| e | `architecture.md` §3.2 物理态的代码块粘贴重复了逻辑态尾部（第 126–150 行中 `wt-*` 与 `forum-service/...` 两段叠在一起，读起来像模块被定义了两遍） | `architecture.md` §3.2 | **architecture.md 不在本次授权修改范围内**，故原样保留、仅记录。这是**该文档自身的排版缺陷**，不影响其结论 |
