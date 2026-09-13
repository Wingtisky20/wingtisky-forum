# M0 · 项目启动与协作基建 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Wingtisky Forum 项目具备"能开工、可交接、可追溯"的全部前提——多模块骨架能编译启动、协作机制就位、本地中间件环境就绪、代码在 GitHub 上、CI 是绿的。

**Architecture:** Maven 多模块单体（9 个模块），父 POM 统一版本管理，`forum-boot` 为唯一启动模块。模块边界由 Maven 依赖约束 + ArchUnit 测试强制。本里程碑**不接入任何中间件依赖**——Redis/Kafka/ES 的 starter 在各自里程碑（M3/M4/M5）按需引入，遵循 YAGNI。

**Tech Stack:** Java 17 · Spring Boot 3.5.3 · Maven 多模块 · ArchUnit · GitHub Actions

## Global Constraints

以下为项目级硬约束，**每个任务都隐式包含**：

- **Java 17**（本机 17.0.16 已装）。**禁止 `javax.*` 包名**，一律 `jakarta.*`。
- **Spring Boot 3.5.3**（2026-09-13 经 Maven Central 核实）。执行时若已有更新稳定版，需记录到 ADR-0001 后再改。
- **禁止使用 Docker**（本机 16G/6 核、Win10 Home 无 WSL2）。
- **所有安装必须落在 D 盘**。C 盘仅剩 ~19G，**严禁**写入。
- **禁止直接 push `main`**。所有改动走 feature 分支 + PR + Squash merge。
- **提交信息必须符合 spec §10.3 严格格式**（\【为什么\】\【改了什么\】\【验证\】\【关联\】）。
- **敏感信息绝不入库**。密码/密钥走环境变量，提供 `.env.example` 占位模板。
- **Minimal tooling**：不引入本计划未列出的任何框架、插件或工具。
- **证据制（spec §9 闸门 1）**：任何"已完成"结论必须附**可复现命令 + 真实输出**。禁止"应该可以了"。
- **依赖防幻觉（spec §9 闸门 3）**：新增依赖必须经 Maven Central 核实真实存在，并 `mvn dependency:tree` 解析成功。

## 本计划的文档书写约定

为避免与 spec 重复（违反单一事实源铁律），本计划对两类产物区别对待：

- **机制类产物**（配置、脚本、POM、CI、CLAUDE.md）——计划中给出**完整内容**，照抄即可。
- **提炼类产物**（charter / collaboration / STATUS / glossary / ADR）——计划中给出**精确的章节清单 + spec 来源映射**，执行时从 spec 对应章节提炼。这些将成为后续**活文档**，与冻结的 spec 分工是：**spec 记录"2026-09-13 我们定了什么"（历史快照，不再修改）；活文档是当前权威。**

---

## File Structure

本里程碑创建的文件及其职责：

```
WingtiskyForum/
├─ pom.xml                                    聚合 POM：模块清单 + 统一版本管理
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
│  ├─ 02-decisions/README.md                  ADR 索引
│  ├─ 02-decisions/ADR-0001-*.md ~ 0008       首批 8 条决策记录
│  ├─ 04-journal/2026-09-13.md                工作日志
│  ├─ 06-runbook/local-setup.md               ES8 / Kafka3.9 安装与启动
│  └─ 06-runbook/troubleshooting.md           踩坑记录
├─ tools/
│  ├─ check-deps.sh                           依赖真实性核查
│  └─ check-doc-refs.sh                       文档-代码一致性核查
├─ forum-common/pom.xml                       9 个模块的 POM 与 Java 源码
├─ forum-domain/pom.xml
├─ forum-infra/pom.xml
├─ forum-module-user/pom.xml
├─ forum-module-content/pom.xml
├─ forum-module-search/pom.xml
├─ forum-module-notify/pom.xml
├─ forum-module-admin/pom.xml
└─ forum-boot/pom.xml  +  WingtiskyForumApplication.java  +  ArchitectureTest.java
```

**职责边界**：`tools/` 只放**校验类**脚本（只读、不修改代码）；`scripts/`（本里程碑不建）留给造数与中间件启停。二者不混。

---

## Task 1: 多模块 Maven 骨架

**Files:**
- Create: `pom.xml`（聚合 POM）
- Create: `forum-common/pom.xml`、`forum-domain/pom.xml`、`forum-infra/pom.xml`、`forum-module-user/pom.xml`、`forum-module-content/pom.xml`、`forum-module-search/pom.xml`、`forum-module-notify/pom.xml`、`forum-module-admin/pom.xml`、`forum-boot/pom.xml`
- Create: `forum-boot/src/main/java/com/wingtisky/forum/WingtiskyForumApplication.java`
- Create: 各模块 `src/main/java/com/wingtisky/forum/<模块名>/package-info.java`

**Interfaces:**
- Consumes: 无（起点）
- Produces: 所有后续任务依赖的模块坐标 `com.wingtisky:forum-<name>:1.0.0-SNAPSHOT`；启动类全限定名 `com.wingtisky.forum.WingtiskyForumApplication`

- [ ] **Step 1: 复核 Spring Boot 版本**

```bash
curl -s --max-time 20 "https://search.maven.org/solrsearch/select?q=g:%22org.springframework.boot%22+AND+a:%22spring-boot-starter-parent%22&core=gav&rows=8&wt=json" | tr ',' '\n' | grep '"v"' | head -8
```
Expected: 输出中含 `3.5.3`。若存在更高的 3.x 稳定版，采用它并在 Task 5 的 ADR-0001 中记录版本变更及理由。**不要采用 4.x / 任何 milestone 版本。**

- [ ] **Step 2: 写聚合 POM**

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
    <version>3.5.3</version>
    <relativePath/>
  </parent>

  <groupId>com.wingtisky</groupId>
  <artifactId>wingtisky-forum</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>wingtisky-forum</name>
  <description>技术社区（对标掘金/牛客）—— Java 后端秋招简历项目</description>

  <modules>
    <module>forum-common</module>
    <module>forum-domain</module>
    <module>forum-infra</module>
    <module>forum-module-user</module>
    <module>forum-module-content</module>
    <module>forum-module-search</module>
    <module>forum-module-notify</module>
    <module>forum-module-admin</module>
    <module>forum-boot</module>
  </modules>

  <properties>
    <java.version>17</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <!-- 版本统一在此管理，禁止在子模块散落版本号 -->
    <redisson.version>3.50.0</redisson.version>
    <caffeine.version>3.2.0</caffeine.version>
    <archunit.version>1.3.0</archunit.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- 内部模块 -->
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-common</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-domain</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-infra</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-module-user</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-module-content</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-module-search</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-module-notify</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.wingtisky</groupId>
        <artifactId>forum-module-admin</artifactId>
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

- [ ] **Step 3: 写子模块 POM**

先核实 ArchUnit 最新稳定版，避免版本幻觉：

```bash
curl -s --max-time 20 "https://search.maven.org/solrsearch/select?q=g:%22com.tngtech.archunit%22+AND+a:%22archunit-junit5%22&core=gav&rows=5&wt=json" | tr ',' '\n' | grep '"v"' | head -5
```
Expected: 得到真实版本号；若非 `1.3.0`，更新聚合 POM 中的 `archunit.version`。

9 个子模块的 artifactId 与依赖如下表（**依赖未列入的模块一律不加**）：

| 模块 | artifactId | 依赖 |
|---|---|---|
| common | `forum-common` | `spring-boot-starter`（不引 web） |
| domain | `forum-domain` | `forum-common` |
| infra | `forum-infra` | `forum-domain`、`forum-common`、`spring-boot-starter` |
| user | `forum-module-user` | `forum-domain`、`forum-common`、`forum-infra`、`spring-boot-starter-web` |
| content | `forum-module-content` | 同 user |
| search | `forum-module-search` | `forum-domain`、`forum-common`、`forum-infra`、`spring-boot-starter` |
| notify | `forum-module-notify` | `forum-domain`、`forum-common`、`forum-infra`、`spring-boot-starter` |
| admin | `forum-module-admin` | 同 user |
| boot | `forum-boot` | 上述全部业务模块 + `forum-infra` + `spring-boot-starter-web` + `spring-boot-starter-actuator` + `spring-boot-starter-test`(test) + `archunit-junit5`(test) |

**每个子模块 POM 的统一模板**（以 `forum-common` 为例，其余模块替换 `<artifactId>` 与 `<dependencies>` 即可）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.wingtisky</groupId>
    <artifactId>wingtisky-forum</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>forum-common</artifactId>
  <name>forum-common</name>
  <description>通用：统一响应体、错误码、异常体系、工具、注解</description>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
  </dependencies>
</project>
```

> **注意**：`forum-boot` 额外需要 `spring-boot-maven-plugin` 的 `repackage` goal（其余模块**不要**加，否则会生成不可执行的 fat jar）：

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

- [ ] **Step 4: 写启动类与各模块 package-info**

`forum-boot/src/main/java/com/wingtisky/forum/WingtiskyForumApplication.java`：

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

每个业务模块建一个 `package-info.java`，用途是**让空模块产生非空 jar** 并声明模块职责。以 `forum-common` 为例（路径 `forum-common/src/main/java/com/wingtisky/forum/common/package-info.java`）：

```java
/**
 * 通用模块：统一响应体、错误码、异常体系、通用工具、注解。
 *
 * <p>边界约束：不依赖任何业务模块，不含业务逻辑。
 */
package com.wingtisky.forum.common;
```

其余模块的包名依次为 `com.wingtisky.forum.domain`、`.infra`、`.module.user`、`.module.content`、`.module.search`、`.module.notify`、`.module.admin`。

- [ ] **Step 5: 验证编译**

Run: `mvn -q clean compile`
Expected: `BUILD SUCCESS`，无 ERROR。**若报 `javax.*` 相关错误说明父 POM 版本不对，回 Step 1。**

- [ ] **Step 6: 验证应用能启动**

Run（后台启动，15 秒后探测健康端点，然后关闭）：
```bash
mvn -q -pl forum-boot -am spring-boot:run &
sleep 25
curl -s http://localhost:8080/actuator/health
kill %1
```
Expected: 输出 `{"status":"UP"}`。日志中可见 `Started WingtiskyForumApplication`。

- [ ] **Step 7: 提交**

```bash
git add pom.xml forum-*/pom.xml forum-*/src
git commit -F - <<'EOF'
feat(build): 建立 Maven 多模块骨架并跑通启动

【为什么】
项目需要模块化单体结构：模块间通过 forum-domain 暴露的接口通信、
不碰对方 DAO，才能在面试中讲清"服务划分规范"；同时单 JVM 保证
本机 16G 内存可承受。骨架先立，后续每个里程碑往对应模块填内容。

【改了什么】
- 父 POM：9 模块聚合 + dependencyManagement 统一版本（Redisson 3.50.0、
  Caffeine 3.2.0、ArchUnit 1.3.0，均经 Maven Central 核实）
- 9 个子模块 POM：依赖严格按职责表配置，未列入的依赖一律不加
- forum-boot：启动类 + repackage 插件（仅此模块配置）
- 各模块 package-info.java 声明职责与边界

【验证】
- `mvn -q clean compile` → BUILD SUCCESS
- `mvn -pl forum-boot -am spring-boot:run` + `curl /actuator/health`
  → {"status":"UP"}，日志见 Started WingtiskyForumApplication

【关联】
Refs: ADR-0002（架构形态：模块化单体）
EOF
```

---

## Task 2: 提交规范落地

**Files:**
- Create: `.gitattributes`
- Create: `.gitmessage`
- Modify: 本地 git 配置（`commit.template`、`core.hooksPath` 暂不设）

**Interfaces:**
- Consumes: Task 1 的仓库
- Produces: 每次 `git commit` 自动带出模板；`.gitattributes` 消除 CRLF 警告

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

- [ ] **Step 1: 写 `CLAUDE.md`**

内容如下（照抄，仅当模块名或版本变化时同步修改）：

````markdown
# Wingtisky Forum — 项目宪法

> 本文件被 Claude Code **每次对话自动加载**。它是接手的第一个入口。
> 完整设计见 `docs/superpowers/specs/2026-09-13-WingtiskyForum-design.md`（历史快照，已冻结）。
> 当前状态见 **`docs/STATUS.md`**（唯一状态源）。

## 项目是什么

技术社区（对标掘金/牛客），Java 后端秋招简历项目。仓库：
https://github.com/Wingtisky20/wingtisky-forum

**不是**旧项目（`D:\aaaDocuments\aaaCommunity\community`）的复刻。旧项目**只读参考，禁止修改**。

## 技术栈白名单

**任何不在下表的技术/依赖，必须先问用户，不得擅自引入。**

| 层 | 选型 | 版本 |
|---|---|---|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.5.3 |
| 安全 | Spring Security | 由 Boot 管理（6.x） |
| 持久层 | MyBatis（待 M2 定，见 §12 开放问题） | — |
| 数据库 | MySQL | 8.0 |
| 缓存 | Redis + Redisson + Caffeine | Redis 5.0.14.1 / Redisson 3.50.0 / Caffeine 3.2.0 |
| 消息 | Kafka（KRaft） | 3.9.x |
| 检索 | Elasticsearch + IK | 8.x |
| 前端 | Vue3 + Vite + Element Plus | — |
| 构建 | Maven 多模块 | — |

**禁止**：Docker · 微服务/注册中心/网关 · 分库分表 · Kafka Streams/Flink · 任何未列入的框架

## 模块边界（强制）

```
forum-common   通用：响应体/错误码/异常/工具/注解 —— 不依赖业务模块
forum-domain   领域模型 + 模块间接口（只放契约，不放实现）
forum-infra    中间件基础设施（机制，不含业务语义）
forum-module-* 业务模块 —— 只能依赖 common/domain/infra，禁止依赖其他业务模块的 internal 包
forum-boot     启动装配，唯一有 main() 的模块
```

**边界线画在「机制 vs 策略」上**：连接、序列化、Key 规范属 infra；缓存存多久、哪些事件幂等属业务模块。
边界由 Maven 依赖约束 + ArchUnit 测试强制（`forum-boot/src/test/.../ArchitectureTest.java`）。

## 提交规范（强制）

格式见 `.gitmessage`。**四节：`【为什么】【改了什么】【验证】【关联】`**
- `【为什么】` 必填——写不出为什么，说明这个提交不该存在
- `【验证】` 必填——附**命令 + 真实结果**，不接受"应该没问题"
- scope：业务用 `m<里程碑>-<域>`（如 `m3-cache`）；基建用 `infra`/`build`/`ci`/`docs`
- **禁止**：`fix bug` / `update` / `优化一下` / 空 body / 主体与 diff 不符
- **禁止直接 push main**；走 feature 分支 + PR + Squash merge

## 会话协议

**开场**——用户说「读文档，继续」时，按序读 3 个文件：
1. 本文件（自动加载）
2. `docs/STATUS.md` —— 唯一入口，其中指明"当前上下文文件"
3. `docs/00-charter/collaboration.md`

然后**必须先向用户复述**：当前阶段 → 上次做到哪 → 本次要做什么 → 有哪些阻塞。
**用户确认后才动手。**

**收场**——五件事缺一不可：
1. 更新 `docs/STATUS.md`（进度 + **证据等级** + 下一步 + 阻塞）
2. 写 `docs/04-journal/YYYY-MM-DD.md`
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

- [ ] **Step 2: 验证自动加载生效**

Run: `ls -la CLAUDE.md` 确认文件在项目根目录。
Expected: 文件存在，且位于 `D:\aaaDocuments\project\WingtiskyForum\CLAUDE.md`。
（真正的"自动加载"验证需在新对话中确认——**这是 M0 收尾时要做的一次实测**，记入 Task 10。）

- [ ] **Step 3: 提交**

```bash
git add CLAUDE.md
git commit -F - <<'EOF'
docs(charter): 新增项目宪法 CLAUDE.md

【为什么】
项目要跨多次对话完成，"新对话能否顺利接手"是硬要求。需要一个每次
对话自动加载、且承载硬约束（技术栈白名单、模块边界、提交规范、
防幻觉闸门）的单一入口文件，避免每个新对话重新解释一遍约定。

【改了什么】
- CLAUDE.md：技术栈白名单（含禁止项）、模块边界、提交规范、会话协议
  （开场三步 / 收场五事）、三条防矛盾铁律、防幻觉六道闸门、环境事实、
  上下文卫生
- 明确其与 spec 的分工：spec 是冻结的历史快照，本文件与 STATUS.md 是活文档

【验证】
- `ls -la CLAUDE.md` → 文件位于项目根目录（自动加载的前提）
- 实际自动加载效果在 Task 10 的新对话中实测

【关联】
Refs: ADR-0001~0008, spec §8
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
## 7. Git 规范速查（分支模型 / 提交格式 / 回滚 / tag）
## 8. 里程碑工作流（feature 分支 → PR → Squash merge → tag）
## 9. 敏感信息处理（环境变量 / 误提交三步应急）
```

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

- `docs/superpowers/specs/2026-09-13-WingtiskyForum-design.md`（§4 架构、§9 防幻觉、§10 Git）
- `docs/00-charter/collaboration.md`（会话协议）

## 里程碑进度

| 里程碑 | 状态 | Tag | 备注 |
|---|---|---|---|
| M0 启动与基建 | 进行中 | — | 见下方任务清单 |
| M1 用户域与安全基座 | 未开始 | — | 亮点 5 |
| M2 内容域核心 | 未开始 | — | |
| M3 亮点1 多级缓存 | 未开始 | — | |
| M4 亮点2 Kafka 异步写 | 未开始 | — | |
| M5 亮点3 ES 检索 | 未开始 | — | |
| M6 亮点4 深翻页 | 未开始 | — | |
| M7 质量与交付 | 未开始 | — | |
| M8 AI 演进 | 未开始 | — | |

## M0 任务清单

| # | 任务 | 状态 | 证据等级 |
|---|---|---|---|
| 1 | 多模块 Maven 骨架 | 未开始 | — |
| 2 | 提交规范落地 | 未开始 | — |
| 3 | 项目宪法 CLAUDE.md | 未开始 | — |
| 4 | 协作文档 | 未开始 | — |
| 5 | ADR 机制与首批 ADR | 未开始 | — |
| 6 | 防漂移脚本 | 未开始 | — |
| 7 | CI 骨架 | 未开始 | — |
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

执行 M0 Task 1：建立多模块 Maven 骨架。

## 待核实

（无）
```

- [ ] **Step 4: 写 `docs/01-requirements/glossary.md` 术语表**

来源：spec §4.1（模块名）、§5（亮点术语）、§6.1（业务术语）。用途是**防止 AI 中途换词**（一会儿"帖子"一会儿"文章"），这也是 spec §9 闸门 5 之外的隐性漂移源。

必须包含的术语条目（每条一行：术语 | 英文/代码标识 | 定义 | 反例「不要叫」）：

| 术语 | 代码标识 | 定义 | 不要叫 |
|---|---|---|---|
| 帖子 | `Post` | 社区的主要内容单元，长文技术分享 | 文章、话题 |
| 评论 | `Comment` | 对帖子的回复，支持二级回复 | 回复、留言 |
| 标签 | `Tag` | 帖子的分类标记 | 分类、话题 |
| 专栏 | `Column` | 帖子的集合，作者自建 | 专题、合集 |
| 用户 | `User` | 注册账号 | 会员、账户 |
| 版主 | `Moderator` | 角色之一，可管理内容 | 管理员（那是 `Admin`） |
| 命中 | cache hit | L1/L2 缓存命中 | 缓存成功 |
| 回源 | fallback to DB | 缓存未命中后查 MySQL | 穿透（那是 cache penetration，是另一回事） |
| 击穿 | cache stampede | 热点 key 失效瞬间大量并发回源 | 穿透、雪崩 |
| 穿透 | cache penetration | 查询不存在的数据，每次都绕过缓存打到 DB | 击穿 |
| 雪崩 | cache avalanche | 大量 key 同时失效 | 击穿、穿透 |

**注意**：击穿/穿透/雪崩三者极易混用，且面试必问。术语表把它们钉死。

- [ ] **Step 5: 提交**

```bash
git add docs/
git commit -F - <<'EOF'
docs(charter): 新增章程、协作协议、状态源与术语表

【为什么】
项目跨多次对话完成，需要一组"活文档"承担交接职责：章程固定范围
（防止范围蔓延）、协作协议固定流程、STATUS.md 作为唯一状态源、
术语表防止 AI 中途换词导致的理解漂移。四者与冻结的 spec 分工明确。

【改了什么】
- docs/00-charter/charter.md（提炼自 spec §1、§6.1，逐条保留"不做的事"）
- docs/00-charter/collaboration.md（提炼自 spec §8、§9、§10）
- docs/STATUS.md：唯一状态源，含里程碑进度表、证据等级图例、阻塞项、
  待核实、以及"当前上下文文件"字段（会话协议依赖它）
- docs/01-requirements/glossary.md：术语表，钉死易混术语（击穿/穿透/雪崩）

【验证】
- `ls docs/00-charter docs/STATUS.md docs/01-requirements` → 四个文件均存在
- 人工核对 charter.md §6 与 spec §1.5 条目数一致（逐条不遗漏）

【关联】
Refs: spec §1、§6.1、§8、§9、§10
EOF
```

---

## Task 5: ADR 机制与首批 8 条 ADR

**Files:**
- Create: `docs/02-decisions/README.md`（ADR 索引，**权威**）
- Create: `docs/02-decisions/ADR-0001-tech-stack-baseline.md` ~ `ADR-0008-git-flow.md`

**Interfaces:**
- Consumes: spec §2（决策摘要）、§12（开放问题）
- Produces: `docs/02-decisions/README.md` —— 被 CLAUDE.md 与 STATUS.md 引用；后续所有新决策在此追加

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
- 状态：`提议` → `已接受` / `已废弃` / `被 ADR-NNNN 取代`

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

| 编号 | 标题 | 状态 | 日期 |
|---|---|---|---|
| [ADR-0001](ADR-0001-tech-stack-baseline.md) | 技术栈基线：JDK17 + Boot3 + ES8 + Kafka KRaft | 已接受 | 2026-09-13 |
| [ADR-0002](ADR-0002-modular-monolith.md) | 架构形态：模块化单体 | 已接受 | 2026-09-13 |
| [ADR-0003](ADR-0003-frontend-vue3.md) | 前端形态：Vue3 + Element Plus 前后端分离 | 已接受 | 2026-09-13 |
| [ADR-0004](ADR-0004-no-docker-locally.md) | 本地不使用 Docker | 已接受 | 2026-09-13 |
| [ADR-0005](ADR-0005-no-microservices.md) | 明确不采用微服务 | 已接受 | 2026-09-13 |
| [ADR-0006](ADR-0006-delivery-github-public.md) | 交付形态：GitHub 公开 + 本地可复现 | 已接受 | 2026-09-13 |
| [ADR-0007](ADR-0007-integration-test-not-in-ci.md) | 集成测试不在 CI 中运行 | 已接受 | 2026-09-13 |
| [ADR-0008](ADR-0008-github-flow.md) | 分支模型：GitHub Flow + Squash merge | 已接受 | 2026-09-13 |
```

- [ ] **Step 2: 写 ADR-0001（技术栈基线）**

来源：spec §3、附录 A 的环境实测。**必须包含**以下要点：
- 背景：本机已装 JDK 17 / Redis 5 / MySQL 8 / Kafka 2.8 / ES 6.8；旧项目用 Boot 2 + JDK 11
- 方案 A：全套沿用旧栈（JDK 11 + Boot 2 + ES 6.8）
- 方案 B：半现代（Boot 3 + ES 8，Kafka 2.8 不动）
- 方案 C（选中）：JDK 17 + Boot 3.5.3 + ES 8.x + Kafka 3.9 KRaft
- 理由核心：**Spring AI / LangChain4j 硬性要求 Java 17+**，M8 的 AI 演进若沿用旧栈必须大返工
- 硬约束：**Spring Boot 3.x 官方 ES 客户端只面向 ES 7.17/8.x**，ES 6.8 的 TransportClient 在 ES 8 已彻底移除
- 后果负面：本机需新装 ES 8 + Kafka 3.9，约多占 1.5G 内存；环境成本约半天

- [ ] **Step 3: 写 ADR-0002（模块化单体）**

来源：spec §4。**必须包含**：
- 三个候选：A 模块化单体（选中）/ B 单模块分层 / C 微服务
- 理由核心：**"知道怎么解耦"和"知道什么时候不解耦"**，后者更难也更是面试官想听的
- 后果负面：模块边界靠自律，用 Maven 约束 + ArchUnit 强制；多模块构建配置繁琐
- 附：**按业务域切分 vs 按技术分层切分**的取舍说明

- [ ] **Step 4: 写 ADR-0003 ~ ADR-0008**

每条按模板写，来源与核心论点如下：

| ADR | 来源 | 必须写进"被否决方案为何不选"的内容 |
|---|---|---|
| 0003 前端 | spec §2 | React（对你目标岗位收益不明显）、无构建静态前端（拿不到框架关键词）、Thymeleaf（与你要求的分离相悖且与旧项目同质） |
| 0004 不用 Docker | spec §2、附录 A | Docker Compose（本机无 Docker/WSL2，16G 内存叠虚拟化拖垮体验）。**必须写清"GitHub 用户可用 Docker Compose 复现"这一保留** |
| 0005 不用微服务 | spec §1.5、§2 | Nacos + 网关方案。**必须写出"什么条件下才该拆微服务"**——这是面试官最可能追问的 |
| 0006 交付形态 | spec §2 | 公网部署（需多 1-2 周 + 服务器成本，4G 机器跑 ES+Kafka 吃紧）。**保留**：架构上已保证配置外置，后期上云不返工 |
| 0007 集成测试不进 CI | spec §7.2、§9 | Testcontainers（标准做法，但本机无 Docker 跑不了）。**必须附"如果有 Docker 应该怎么做"**，并记下 M7 的评估结论 |
| 0008 Git 流程 | spec §10.2 | Git Flow（对单人项目过重）、直接 push main（无 PR 即无变更记录） |

- [ ] **Step 5: 验证 ADR 完整性**

Run:
```bash
ls docs/02-decisions/ | grep -c '^ADR-'
grep -c '^| \[ADR-' docs/02-decisions/README.md
```
Expected: 两条命令输出均为 `8`（文件数与索引条目数一致）。

- [ ] **Step 6: 提交**

```bash
git add docs/02-decisions/
git commit -F - <<'EOF'
docs(adr): 建立 ADR 机制并落首批 8 条决策记录

【为什么】
面试官问的是"你为什么这么设计"，而不是"你做了什么"。没有 ADR 的
commit 只有一半信息——commit 说"我这么做了"，ADR 才说"我为什么
没选另一个方案"。8 条决策此前只存在于 spec 的汇总表里，缺少
"候选方案 / 后果 / 被否决方案为何不选"三节，不足以支撑追问。

【改了什么】
- docs/02-decisions/README.md：ADR 索引（权威清单）+ 模板 + 何时写/不写
- ADR-0001 技术栈基线（核心约束：Spring AI/LangChain4j 要求 Java 17+）
- ADR-0002 模块化单体（核心：知道何时不解耦比知道怎么解耦更难）
- ADR-0003 前端 Vue3 / ADR-0004 不用 Docker / ADR-0005 不用微服务
- ADR-0006 交付形态 / ADR-0007 集成测试不进 CI / ADR-0008 GitHub Flow
- 每条均含"后果（含负面）"与"被否决方案为何不选"两节

【验证】
- `ls docs/02-decisions/ | grep -c '^ADR-'` → 8
- `grep -c '^| \[ADR-' docs/02-decisions/README.md` → 8（索引与文件一一对应）

【关联】
Refs: spec §2、§12
EOF
```

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
SRC_DIRS=(forum-common forum-domain forum-infra forum-module-user \
          forum-module-content forum-module-search forum-module-notify \
          forum-module-admin forum-boot)

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
- `check-doc-refs` → 可能报告 `docs/CLAUDE.md` 中引用的 `ArchitectureTest` 尚不存在（**这是预期的正确行为**——Task 1 未创建该类）。若报告此项，进入 Step 5 修复；若报其他项，逐条核实文档是否真的写错了。

- [ ] **Step 5: 修复 Step 4 发现的漂移**

若报 `ArchitectureTest` 不存在：该引用在 CLAUDE.md 中标注了完整路径，属于**文档先行、代码未到**。此时应创建该测试类（Task 7 的内容）而非删掉引用。**若 Step 4 无报错，跳过此步。**

- [ ] **Step 6: 提交**

```bash
git add tools/
git commit -F - <<'EOF'
feat(tools): 新增依赖与文档-代码一致性核查脚本

【为什么】
spec §9 闸门 3 与闸门 6 此前只有原则没有手段：依赖幻觉（引用了不存在
的库）和文档漂移（文档描述了一个不存在的类）都无法自动发现。研究
显示这两类问题的危害分别是供应链安全与交接失效，且人工核查会随
文档增长而失效。

【改了什么】
- tools/check-deps.sh：mvn dependency:resolve 验证依赖可解析 +
  检查父 POM 之外是否存在散落版本号
- tools/check-doc-refs.sh：提取文档中形如 com.wingtisky.* 与
  Xxx{Application,Service,Mapper,Controller,Config,Util,Test} 的引用，
  校验其在 Java 源码中真实存在
- tools/README.md：说明职责、对应闸门、为何用 shell 而非 Maven 插件

【验证】
- `bash tools/check-deps.sh` → [check-deps] 通过，退出码 0
- `bash tools/check-doc-refs.sh` → 退出码与预期一致（发现的漂移项
  在 Step 5 处理后复跑通过）

【关联】
Refs: spec §9 闸门 3、闸门 6
EOF
```

---

## Task 7: 架构边界测试 + CI 骨架

**Files:**
- Create: `forum-boot/src/test/java/com/wingtisky/forum/ArchitectureTest.java`
- Create: `.github/workflows/ci.yml`
- Create: `README.md`

**Interfaces:**
- Consumes: Task 1 的模块结构、Task 6 的两个脚本
- Produces: CI 流水线（push/PR 触发）；`README.md` 作为仓库门面

- [ ] **Step 1: 写失败的架构测试**

`forum-boot/src/test/java/com/wingtisky/forum/ArchitectureTest.java`：

```java
package com.wingtisky.forum;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构边界规则的强制检查（spec §4.2）。
 *
 * <p>这些规则不是"建议"——违反即测试失败。目的是让模块解耦从口头约定
 * 变成 CI 能拦住的硬约束，避免代码量增长后边界退化成泥球。
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.wingtisky.forum");
    }

    @Test
    @DisplayName("common 不得依赖任何业务模块")
    void commonShouldNotDependOnBusinessModules() {
        noClasses().that().resideInAPackage("..forum.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..forum.module.user..",
                        "..forum.module.content..",
                        "..forum.module.search..",
                        "..forum.module.notify..",
                        "..forum.module.admin..")
                .check(classes);
    }

    @Test
    @DisplayName("业务模块之间不得互相依赖")
    void businessModulesShouldNotDependOnEachOther() {
        String[] businessModules = {
                "..forum.module.user..", "..forum.module.content..",
                "..forum.module.search..", "..forum.module.notify..",
                "..forum.module.admin.."
        };
        for (String from : businessModules) {
            noClasses().that().resideInAPackage(from)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            java.util.Arrays.stream(businessModules)
                                    .filter(to -> !to.equals(from))
                                    .toArray(String[]::new))
                    .because("业务模块间必须通过 forum-domain 的接口通信，不得直接依赖")
                    .check(classes);
        }
    }

    @Test
    @DisplayName("domain 不得依赖 infra（契约不依赖实现）")
    void domainShouldNotDependOnInfra() {
        noClasses().that().resideInAPackage("..forum.domain..")
                .should().dependOnClassesThat().resideInAPackage("..forum.infra..")
                .because("forum-domain 只放契约，不能反向依赖基础设施实现")
                .check(classes);
    }
}
```

- [ ] **Step 2: 运行测试，确认基线通过**

Run: `mvn -q -pl forum-boot -am test -Dtest=ArchitectureTest`
Expected: 3 个测试全部 PASS。

**为什么此时期望通过而非失败**：当前模块是空的，没有违规可抓。这些规则的价值在 M1 之后开始体现——它们是**守卫**而非**待办**。若此时失败，说明 ArchUnit 版本或包名配置有误，需修正后再继续。

- [ ] **Step 3: 验证规则真的能抓住违规（关键步骤）**

**这是防止"测试永远通过从而形同虚设"的必要验证。** 临时在 `forum-module-content` 中加一个违规类：

创建临时文件 `forum-module-content/src/main/java/com/wingtisky/forum/module/content/TempViolation.java`：

```java
package com.wingtisky.forum.module.content;

import com.wingtisky.forum.module.user.PlaceholderInUser;

/** 临时违规类：仅用于验证 ArchUnit 规则生效，验证后立即删除。 */
public class TempViolation {
    private PlaceholderInUser ref;
}
```

同时在 `forum-module-user` 创建 `PlaceholderInUser.java`：

```java
package com.wingtisky.forum.module.user;

/** 临时类：仅用于验证 ArchUnit 规则生效，验证后立即删除。 */
public class PlaceholderInUser {
}
```

Run: `mvn -q -pl forum-boot -am test -Dtest=ArchitectureTest`
Expected: **FAIL**，报错指出 `com.wingtisky.forum.module.content.TempViolation` 依赖了 `..forum.module.user..`。

若通过 → **规则失效，必须先修好再继续**（检查包名通配符与 import 范围）。

- [ ] **Step 4: 删除临时类并复测**

```bash
rm forum-module-content/src/main/java/com/wingtisky/forum/module/content/TempViolation.java
rm forum-module-user/src/main/java/com/wingtisky/forum/module/user/PlaceholderInUser.java
mvn -q -pl forum-boot -am test -Dtest=ArchitectureTest
```
Expected: 3 个测试全部 PASS（回到基线）。

> **为什么坚持做 Step 3**：一个从未失败过的测试不是测试，是装饰。spec §9 闸门 2 要求"反面对照"——这条规则实验就是它的体现。

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

<一句话简介：技术社区，Java 后端项目>

## 这是什么
<2-3 句：技术社区（对标掘金/牛客），用于展示后端工程能力>

## 技术栈
<表格，与 CLAUDE.md 的技术栈白名单一致>

## 架构
<模块表格 + 一句话说明模块边界规则>

## 本地运行
<前置要求：JDK 17、Maven、MySQL 8、Redis 5、Kafka 3.9、ES 8>
<步骤：1. 启动中间件 2. 配置环境变量 3. mvn spring-boot:run>
<注意：中间件安装详见 docs/06-runbook/local-setup.md>

## 项目状态
<如实标注：M0 进行中（骨架阶段，业务功能未实现）>

## 文档导航
<指向 CLAUDE.md / docs/STATUS.md / docs/02-decisions/ / spec>

## License
MIT
```

> **诚实原则**：README 此时**必须**明确标注"骨架阶段，业务功能尚未实现"。README 声称了未实现的功能，是 spec §9 闸门 4 针对的那类不一致。

- [ ] **Step 7: 提交**

```bash
git add forum-boot/src/test .github/ README.md
git commit -F - <<'EOF'
feat(build): 新增架构边界测试、CI 流水线与 README

【为什么】
spec §4.2 要求模块边界"不靠自觉"——用 Maven 约束 + ArchUnit 强制。
但一个从未失败过的测试不是测试而是装饰，因此本次额外用临时违规类
验证了规则真的能拦住跨模块依赖，验证后删除。同时补上 CI 与门面。

【改了什么】
- ArchitectureTest：三条规则（common 不依赖业务、业务模块互不依赖、
  domain 不依赖 infra）
- .github/workflows/ci.yml：编译 → 单测 → 闸门3 → 闸门6，
  push/PR 触发。集成测试不在 CI 中运行（ADR-0007）
- README.md：门面，如实标注当前为 M0 骨架阶段、业务功能未实现

【验证】
- 基线：`mvn -pl forum-boot -am test -Dtest=ArchitectureTest` → 3 passed
- 反面：临时加入 content→user 的跨模块依赖 → 测试 FAIL（规则生效）
- 删除临时类后复跑 → 3 passed

【关联】
Refs: ADR-0007、spec §4.2、§9 闸门 2/3/6
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

这是 spec 未预见但本次查版本时发现的风险：**Redisson 3.50.0 可能使用 Redis 5.0.14.1 不支持的命令**。

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

```bash
git add docs/06-runbook/ scripts/
git commit -F - <<'EOF'
feat(runbook): 新装 ES 8.18.3 + Kafka 3.9.1(KRaft) 并落地环境文档

【为什么】
spec §3 定了技术栈基线为 ES 8 + Kafka KRaft，本机现有 ES 6.8.23 与
Kafka 2.8.1(+ZK) 均为旧项目资产、不满足要求，需新装。旧版本不删除，
避免影响旧项目。此步是 M0 的硬前置——没有中间件，M3/M4/M5 无法开工。

【改了什么】
- 新装 ES 8.18.3 到 D:\aaaSoftware（未覆盖 6.8.23），关闭 xpack.security
  （仅本地）、堆压至 1g、装上 IK 分词器
- 新装 Kafka 3.9.1 KRaft 到 D:\aaaSoftware，**不再需要 ZooKeeper 进程**
- docs/06-runbook/local-setup.md：含生产环境差异说明
- docs/06-runbook/troubleshooting.md：记录本次实际踩的坑
- scripts/：中间件启停脚本

【验证】
- `curl localhost:9200` → cluster_name = wingtisky-forum
- `curl /_analyze` (ik_max_word, "多级缓存与Redisson分布式锁") → 分词正确
- `kafka-topics --describe --topic smoke-test` → 3 分区，Leader 均非 none
- `tasklist | grep -i zookeeper` → 无输出（KRaft 生效）
- `redis-cli INFO server` → redis_version 已记录

【关联】
Refs: ADR-0001、spec §3、§6.2 M0
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
  --description "技术社区（对标掘金/牛客）· Java 17 + Spring Boot 3 · 多级缓存 / Kafka 异步 / ES 检索 / 深翻页治理 / RBAC + 滑动窗口限流"
```
Expected: 输出仓库 URL `https://github.com/Wingtisky20/wingtisky-forum`。

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

- [ ] **Step 6: 提交（如有修复）**

若 Step 4/5 产生了修复，按格式单独提交并推送。

---

## Task 10: M0 收尾

**Files:**
- Modify: `docs/STATUS.md`
- Create: `docs/04-journal/2026-09-13.md`
- Create: git tag `m0-done`

**Interfaces:**
- Consumes: Task 1-9 的全部产物
- Produces: `m0-done` tag；更新后的 STATUS.md（M1 的起点）

- [ ] **Step 1: 逐项核对 M0 闸门（spec §6.2）**

```bash
echo "=== 1. 编译 ==="; mvn -q clean compile && echo "OK"
echo "=== 2. 架构测试 ==="; mvn -q -pl forum-boot -am test -Dtest=ArchitectureTest && echo "OK"
echo "=== 3. 防漂移脚本 ==="; bash tools/check-deps.sh && bash tools/check-doc-refs.sh
echo "=== 4. 提交模板 ==="; git config --get commit.template
echo "=== 5. ES ==="; curl -s http://localhost:9200 | head -3
echo "=== 6. Kafka（无 ZK）==="; tasklist | grep -ci zookeeper || echo "0 个 ZK 进程"
echo "=== 7. 远程 ==="; git remote -v
echo "=== 8. CI ==="; gh run list --limit 1
```
Expected: 全部符合 spec §6.2 的 M0 闸门要求。**任何一项不达标，M0 不算完成**——这是 spec §9 闸门 1（证据制）的直接应用。

- [ ] **Step 2: 实测"新对话能否接手"（M0 最关键的一次验证）**

**在一个全新对话中**（不携带本次上下文），只输入：

```
读文档，继续
```

观察新对话是否能：① 找到并读取 `CLAUDE.md`、`docs/STATUS.md`、`collaboration.md`；② 正确复述当前阶段与下一步；③ 不提出与既有决策冲突的建议。

**若③出现冲突**（如建议引入 Docker、建议用微服务、建议改技术栈）→ **说明 CLAUDE.md 的约束写得不够醒目，必须回头加固**。这是本次 M0 交付物最重要的质量检验，不可省略。

- [ ] **Step 3: 更新 `docs/STATUS.md`**

把所有 M0 任务的状态改为「完成」，证据等级按实际情况填写（真实链路跑过的填 `[REAL]`），并更新：
- 顶部"最后更新"日期
- "当前阶段" → 准备进入 M1
- "下一步" → M1 的第一步
- "待核实" → 填入尚未解决的事项（如 Redisson/Redis 5 兼容性待 M3 验证）

- [ ] **Step 4: 写 `docs/04-journal/2026-09-13.md`**

必须包含：

```markdown
# 2026-09-13 工作日志

## 本次会话目标
<如实填写>

## 实际完成
<逐项，与 STATUS.md 一致但写成叙述>

## 关键决策
<指向本次新增的 ADR 编号>

## 遇到的问题与解决
<如实填写，含未解决的>

## 回滚记录
<本次有无回滚；无则写"无">

## 遗留与风险
<如实填写>

## 下次会话的起点
<一句话：从哪继续>
```

- [ ] **Step 5: 打 tag 并推送**

```bash
git add -A
git commit -F - <<'EOF'
docs(status): M0 完成，更新状态源与工作日志

【为什么】
M0 的全部闸门已逐项验证通过，需要把状态沉淀回唯一状态源，
使新对话可以从 M1 直接接手；同时按 spec §10.7 打里程碑 tag，
建立可对照、可回滚的锚点。

【改了什么】
- docs/STATUS.md：M0 全部任务标记完成并填写证据等级；
  当前阶段推进至 M1；补充"待核实"项
- docs/04-journal/2026-09-13.md：本次会话的完整记录

【验证】
- M0 闸门逐项核对命令与输出见 STATUS.md
- 新对话"读文档，继续"实测可正确接手（无决策冲突）

【关联】
Refs: spec §6.2 M0 闸门、§9 闸门 1
EOF

git tag -a m0-done -m "M0 项目启动与协作基建完成：多模块骨架 / 协作文档 / ADR / 防漂移脚本 / CI / ES8+Kafka3.9 / GitHub 公开仓库"
git push origin main --follow-tags
```

- [ ] **Step 6: 发 GitHub Release**

```bash
gh release create m0-done --title "M0 · 项目启动与协作基建" --notes-file docs/04-journal/2026-09-13.md
```
Expected: Release 创建成功，可在仓库页面看到。

- [ ] **Step 7: 向用户报告**

按 spec §8.2 的收场要求，向用户如实报告：真实改了什么、验证到什么程度（附证据）、遗留什么问题、下一个里程碑是什么。

---

## Self-Review

**1. Spec coverage** — 逐节核对 spec，确认有任务覆盖：

| spec 章节 | 覆盖任务 |
|---|---|
| §3 技术栈基线 | Task 1（POM）、Task 8（中间件）、ADR-0001 |
| §4.1 模块划分 | Task 1 |
| §4.2 边界规则（ArchUnit） | Task 7 |
| §4.3 机制 vs 策略 | Task 1（package-info 中声明边界） |
| §6.2 M0 闸门 | Task 10 Step 1 |
| §6.2 M0 任务内容（仓库/骨架/CLAUDE/STATUS/ADR/CI/中间件/目录/术语表） | Task 1–9 全覆盖 |
| §8.1 目录结构 | Task 1、3、4、5、6、7、8 |
| §8.2 会话协议 | Task 3（写入 CLAUDE.md）、Task 4（写入 collaboration.md）、Task 10 Step 2（实测） |
| §8.3 三条铁律 | Task 3、Task 4 |
| §9 闸门 1（证据制） | Task 4（STATUS.md 图例）、Task 10 Step 1 |
| §9 闸门 2（反验证剧场） | Task 7 Step 3（ArchUnit 反面对照） |
| §9 闸门 3（依赖防幻觉） | Task 1 Step 1/3、Task 6 |
| §9 闸门 4（提交 diff 对账） | Task 2（模板）、所有任务的提交步骤 |
| §9 闸门 5（高危区点名） | Task 3（写入 CLAUDE.md） |
| §9 闸门 6（文档-代码对账） | Task 6 |
| §10.1 仓库与身份 | 前置已完成（git init + 身份配置） |
| §10.2 分支模型 | Task 9 Step 4（分支保护）、Task 9 Step 5（走 PR） |
| §10.3 提交规范 | Task 2 |
| §10.4 提交粒度 | Task 2、所有提交步骤 |
| §10.5 回滚规范 | Task 4（写入 collaboration.md） |
| §10.6 追溯命令 | Task 4（写入 collaboration.md） |
| §10.7 Tag 与 Release | Task 10 |
| §10.8 CI | Task 7 |
| §10.9 敏感信息 | Task 9 Step 1 |
| §11 风险表 | Task 8（ES 降级路径）、Task 10 Step 2（可读性验证） |

**未覆盖需说明**：`docs/01-requirements/prd.md`、`domain-model.md`、`docs/03-design/*`、`docs/05-interview/*`、`docs/assets/*` 属后续里程碑（M1 起）产出，本计划（M0）不涉及。

**2. Placeholder scan** — 已检查：无 TBD / TODO / "实现细节待定" / "类似 Task N"。所有代码步骤含完整内容；所有验证步骤含具体命令与预期输出。Task 5 的 ADR-0003~0008 采用"精确论点清单 + 来源映射"而非全文，理由见本计划开头的"文档书写约定"——这些 ADR 的论点已由 spec §2 完全确定，属提炼而非创作。

**3. Type consistency** — 已核对：
- 模块 artifactId 在 Task 1、6、7 中一致（`forum-common` 等 9 个）
- 包名 `com.wingtisky.forum.*` 在 Task 1（package-info）、Task 7（ArchUnit 规则）中一致
- 脚本路径 `tools/check-deps.sh`、`tools/check-doc-refs.sh` 在 Task 6、7 中一致
- 状态文件路径 `docs/STATUS.md` 在 Task 4、10 与 CLAUDE.md 中一致
- 版本变量 `redisson.version` / `caffeine.version` / `archunit.version` 在 Task 1 中定义并被引用

**4. 刻意保留的空白**（非疏漏）：
- Task 8 Step 6 的 Kafka 下载 URL 标注"以 Apache 归档实际存在的为准"——因为凭记忆写死版本 URL 正是闸门 3 要防的行为
- Task 8 Step 10 的 troubleshooting 内容留待执行时如实填写——编造踩坑经历违反诚实原则
