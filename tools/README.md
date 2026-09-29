# tools/ — 防漂移校验脚本

只读校验，**不修改任何代码**。被 `.github/workflows/ci.yml` 调用（Task 7 接入）。

| 脚本 | 职责 | 对应闸门 |
|---|---|---|
| `check-deps.sh` | 依赖真实可解析 + 版本号不散落在子模块 | spec §9 闸门 3 |
| `check-doc-refs.sh` | 活文档中引用的类/包在代码中真实存在 | spec §9 闸门 6 |

## 本地运行

```bash
bash tools/check-deps.sh;  echo "退出码: $?"
bash tools/check-doc-refs.sh; echo "退出码: $?"
```

退出码 `0` = 通过，`1` = 发现问题。

## 为什么是 shell 而非 Maven 插件

遵循 **minimal tooling**（spec §1.5）。两个脚本合计不足 120 行，引入 `maven-enforcer-plugin` 之类的构建插件反而增加维护面——而且**插件自身的版本号又是一个新的幻觉来源**，与这套脚本要防的东西同类。

## 两处刻意的设计取舍

### `check-doc-refs.sh` 只扫「活文档」，不扫计划书与规格

活文档 = 描述**当前系统长什么样**的文档（`CLAUDE.md` / `STATUS` / charter / ADR / 04-log / glossary / architecture / runbook）。

**排除 `docs/superpowers/plans/` 与 `docs/superpowers/specs/`**，因为它们描述的是**尚不存在的代码**：

- `plans/` 是施工图，写着"这一步要创建 `XxxClass`"
- `specs/` 是冻结的历史快照

实证：本脚本首次运行时报了 3 处，其中 2 处（`TempViolation`、`PlaceholderInTradeProduct`）来自计划书描述的**临时测试文件**——它们被建出来只为验证 ArchUnit 能拦住跨域依赖，验完即 `rm`，**永远不该出现在代码里**。一个天天报红的检查等于没有检查。

### 引用类型只匹配两类，不匹配所有 CamelCase

只查：

1. `com.wingtisky.*` 全限定名
2. `Xxx{Application,Service,Mapper,Controller,Config,Util,Test}` 形式的类名

**为什么不放宽**：放宽正则会把 `Post`、`Comment`、`Coupon`、`Order` 这类**术语表里的领域模型名**一并扫进来——它们是 M2/M7/M8 才实现的，现在确实不存在，但**不是漂移，是计划**。放宽即误报，误报即失信，失信的检查没人看。

## 已知局限（诚实记录）

| 局限 | 说明 |
|---|---|
| **抓不了语义漂移** | 只做文本匹配。文档说"用 Redisson 防击穿"而代码用的是别的方案，脚本看不出来 |
| **抓不了旧模块名残留** | `forum-common` / `forum-boot` 这类旧名字**出现在 ADR 里是合法的**——ADR 的职责就是记录"当初叫什么、后来改成什么"（见 `ADR-0002` 的改名对照表）。因此不能用"出现旧名字即报错"的规则 |
| **当前是空过** | 截至 2026-09-19，活文档里还没有形如 `XxxService` 的代码引用，所以脚本报「暂无可检查的代码引用」。它已用反面对照验证过有效性（注入假全限定名 → 正确报错并指出出处），**从 M1 写接口文档起开始真正咬人** |
| **需要 Git Bash** | Windows 上跑 `bash tools/*.sh` |

## 验证脚本本身是否有效（反面对照）

两个脚本都做过"注入错误 → 确认报错 → 还原"的实测，不是只看它输出"通过"：

```bash
# check-deps：往子模块 POM 塞一个散落版本号
sed -i 's|</dependencies>|  <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>\n  </dependencies>|' wt-common/pom.xml
bash tools/check-deps.sh   # → 退出码 1，指名 ./wt-common/pom.xml:22
git checkout -- wt-common/pom.xml
```

> **只验"通过"是单边数据**：一个永远返回 0 的脚本也能"通过"。必须验它**该报的时候真的会报**。
