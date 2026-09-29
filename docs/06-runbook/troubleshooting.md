# 踩坑记录

> 用途有二：① 防止后续反复踩同一个坑；② 面试时"我遇到过什么问题、怎么定位、怎么解决"是最有说服力的素材。
> **只记真实的坑，不要编。**

---

## 2026-09-13 · git push 到 GitHub 失败（gh 能用但 git 不能用）

**现象**

```
gh repo create ...            → ✅ 成功
git push -u origin main       → ❌ fatal: unable to access
                                 'https://github.com/...': Recv failure: Connection was reset
                                 再试一次变成：Failed to connect to github.com port 443 after 21146 ms
```

**诊断过程**（这一步比结论更值得记）

```bash
curl -o /dev/null -w "%{http_code}" https://api.github.com   # → 200 ✅
curl -o /dev/null -w "%{http_code}" https://github.com       # → 000 ❌
```

**关键线索**：`api.github.com` 通、`github.com` 不通。

- `gh` CLI 走的是 **api.github.com** → 所以它能创建仓库
- `git push` 走的是 **github.com** → 所以它失败

这解释了"为什么 gh 能用但 git 不能用"这个看起来矛盾的现象。**遇到类似症状先分别测这两个域名，能立刻定位方向。**

**根因**

本机通过 Clash Verge 代理访问外网，而 **git 没有配置代理**（`git config --get http.proxy` 为空）。
Clash 未提供常见端口（7890/7897 等），实际混合端口是 **18569**。

**解决**

```bash
# 探测可用代理端口（逐个试，找到能返回 200 的那个）
for p in 7890 7897 10809 8440 8588 8590 18569; do
  curl -s -o /dev/null -w "$p → %{http_code}\n" --max-time 5 \
    -x "http://127.0.0.1:$p" https://api.github.com
done

# 配置给 git（用 --local 只影响本仓库，不动全局设置，也不会被提交）
git config --local http.proxy  http://127.0.0.1:18569
git config --local https.proxy http://127.0.0.1:18569
```

**注意事项**

- 代理只在本机 **Clash 运行时有效**。Clash 关闭后 `git push` 会再次失败——**不是仓库坏了，是代理没开**
- 该配置写在 `.git/config`（`--local`），**不会提交、不影响你其他项目**
- 若哪天端口变了（Clash 换配置），用上面的探测命令重新找

**耗时**：约 10 分钟

---

## 2026-09-13 · `git add` 持续报 "LF will be replaced by CRLF"

**现象**：每次 `git add` 都刷一屏该警告。

**根因**：仓库缺少 `.gitattributes`，Windows 开发 + GitHub 协作时换行符无统一规则；长期会造成"整文件变更"的假 diff，把真实改动淹没。

**解决**：新增 `.gitattributes`（`* text=auto`，脚本强制 LF、批处理强制 CRLF、二进制标记 binary）。

**验证**：`git ls-files --eol CLAUDE.md` → `i/lf w/lf attr/text`，仓库内已统一存为 LF。

**注意**：配置后 `git add` 仍可能提示一次 "LF will be replaced by CRLF"——这是 `.gitattributes` 生效后的**提示性**消息（告知检出时会转 CRLF），不是错误。

---

## 2026-09-19 · 推送新增 CI 文件被 GitHub 拒绝（缺 `workflow` scope）

**现象**：

```
! [remote rejected] feature/m0-bootstrap -> feature/m0-bootstrap
  (refusing to allow an OAuth App to create or update workflow
   `.github/workflows/ci.yml` without `workflow` scope)
```

注意：**整个推送失败，不只是那一个文件**——同批次的其余提交也一起被拒，本地提交本身不受影响。

**根因**：GitHub 规定，能新增或修改 `.github/workflows/` 的凭证必须带 `workflow` scope（CI 配置能执行任意命令，属敏感操作）。项目初始化时 `gh` 授权的 scope 是 `gist, read:org, repo`，**不含 `workflow`**。凡是改动 CI 配置的提交都会撞上，不是偶发。

**解决**：

```powershell
& "$env:USERPROFILE\bin\gh.exe" auth refresh -h github.com -s workflow
```

走一次设备授权：终端打印一次性代码 → 在浏览器 `https://github.com/login/device` 输入 → 授权。

**两个额外的坑**：

1. **`gh` 装在 `C:\Users\w\bin\`，不在 PowerShell 的 PATH 上**（Git Bash 里可用）。所以 PowerShell 中必须用完整路径，否则报 `无法将"gh"项识别为 cmdlet`。备选：改用 Git Bash 执行，或把该目录加进 PATH。
2. **那个一次性代码是命令打印在终端里的**，不发邮箱、不显示在 GitHub 网页、也和"哪台电脑登录过"无关。看不到它，通常是命令根本没跑起来。

**验证**：

```bash
gh auth status   # → Token scopes: 'gist', 'read:org', 'repo', 'workflow'
```

随后推送成功。

**耗时**：约 20 分钟，其中大部分花在"那串码在哪"的误解上。

---

## 2026-09-19 · Windows 上写中间件启停脚本踩的三个坑

装 ES 8 + Kafka 3.9 时写的两个 `.bat` 脚本**连报三次错**，逐个记下来。三条都不是本项目特有，凡是 Windows + 中文环境 + Git Bash 的组合都会遇到。

### 坑 1：`.bat` 里的中文注释会把整个脚本弄崩

**现象**：脚本里写了中文 `REM` 注释，执行时报一串

```
'按物理内存的一半算堆，16G' is not recognized as an internal or external command
'elasticsearch.bat' is not recognized as an internal or external command
```

**根因**：`.bat` 文件在 git 里存为 **UTF-8**，而 zh-CN 控制台上的 `cmd.exe` 按 **GBK** 解码它。中文注释的字节被错解后，行结构被拆散——**注释的残片被当成命令执行，连后面正常的 `call` 行也一起失效**。

**试过但没用的做法**：在脚本开头加 `chcp 65001`。不管用——cmd 读取文件内容发生在切换代码页之前，该崩还是崩。

**解决**：**`.bat` 文件一律只用 ASCII**。中文解释放 `.md` 文档里（那份是给人看的，UTF-8 完全没问题）。
一开始担心"注释不写中文不友好"，但脚本是给机器跑的——**能跑起来**比注释好看重要得多。

### 坑 2：`call elasticsearch.bat` 报"不是内部或外部命令"，但 `cd` 明明成功了

**现象**：脚本里 `cd /d D:\...\bin` 之后 `call elasticsearch.bat`，报找不到；用**完整路径**调用同一个文件却正常。

**根因**：**Git Bash 会设置 `NoDefaultCurrentDirectoryInExePath=1` 并传给子进程**。这个变量一开，`cmd.exe` 就不再从当前目录查找可执行文件——所以即使 `cd` 成功，相对文件名也找不到。

**验证**：

```bash
cmd //c "set NoDefault"     # → NoDefaultCurrentDirectoryInExePath=1
```

**解决**：显式写出路径前缀——`call .\elasticsearch.bat`。加 `.\` 就绕过了当前目录查找的限制。

> **注意适用范围**：从普通 cmd / PowerShell（不走 Git Bash）启动脚本时不会有这个问题，`.bat` 里的相对路径是正常的。**是 Git Bash 把它们的环境变量传染了下去。** 但不加 `.\` 的话，从 Git Bash 调用就会失败——而本项目大量命令都在 Git Bash 里跑，所以统一加。

### 坑 3：Kafka 格式化把数据写到了 `D:\tmp`

**现象**：还没配 `log.dirs` 就先跑了 `kafka-storage.bat format`，输出

```
Formatting metadata directory /tmp/kraft-combined-logs
```

`/tmp` 在 Windows 上不是 Git Bash 的 `/tmp`，而是**当前盘根目录下的 `tmp`** ——实际落在了 `D:\tmp\kraft-combined-logs`（当时的当前盘是 D）。

**根因**：Kafka 默认配置 `log.dirs=/tmp/kraft-combined-logs` 是 Linux 风格路径，Windows 上被解析成盘相对路径。

**解决**：**先改配置再格式化**——`config/kraft/server.properties` 里把 `log.dirs` 指向真实路径，然后重新格式化，并删掉误建目录。

**教训**：**顺序错了要返工**。"生成集群 ID → 格式化"看起来是准备阶段，但格式化会**按当时生效的配置**落盘，所以配置必须在它之前改完。

---

## 2026-09-19 · Kafka 3.9.1 下载极慢（归档版本没有国内镜像）

**现象**：下载 `kafka_2.13-3.9.1.tgz`（116 MB），直连 `archive.apache.org` 约 **8 KB/s**，预计两个多小时；走 Clash 代理约 **16 KB/s**，仍然很慢。

**根因**：**Kafka 3.9.x 已是归档版本**，而国内 Apache 镜像（清华 / 阿里 / 中科大 / 南大 / 网易 / 华为云）**只保留当前发布版**——实测这些镜像上只有 4.1.2 / 4.2.1 / 4.2.2 / 4.3.1，**3.9.x 一律 404**。归档只能从 `archive.apache.org` 取。

**解决**：走代理 + 断点续传 + 自动重试，**放后台跑**（实测最终约 8 MB/s，总耗时约 20 分钟）：

```bash
curl -x http://127.0.0.1:18569 -L -C - --retry 20 --retry-all-errors \
  -o kafka_2.13-3.9.1.tgz \
  "https://archive.apache.org/dist/kafka/3.9.1/kafka_2.13-3.9.1.tgz"
```

**关键点**：**它不该阻塞其他工作**。正确的顺序是——Kafka 丢后台下载，**同时去装 ES**。两个中间件本来就没有依赖关系，串行等它纯属浪费。

**顺带做的一件事**：下载完成后用官方 `.sha512` **校验了文件完整性**（断点续传 + 代理，不验不敢用）。注意 `/dist/` 的校验文件格式是 `<文件名>: <哈希>`，且哈希是**大写**的——直接字符串比较会误报"损坏"，要先剥掉前缀并大小写归一。

**耗时**：下载约 20 分钟（后台，未阻塞其他工作）

---

## 2026-09-19 · `check-deps.sh` 在 CI 上必然失败（本地却一直"通过"）

**现象**：M0 的 PR 一开，CI 第一次真实运行就红了，报错：

```
[ERROR] Failed to execute goal on project wt-domain:
  Could not resolve dependencies for project com.wingtisky:wt-domain:jar:1.0.0-SNAPSHOT
[ERROR] dependency: com.wingtisky:wt-common:jar:1.0.0-SNAPSHOT (compile)
[ERROR]   Could not find artifact com.wingtisky:wt-common:jar:1.0.0-SNAPSHOT
```

**根因**：本项目的模块之间有内部依赖（`wt-domain` → `wt-common` 等），**这些坐标不在任何远程仓库里，只能由 Maven 的 reactor 提供**。而 reactor 解析依赖看的是**本次 Maven 会话的状态**——只有在本会话里被 `compile` 过的模块，它的 `target/classes` 才会被当作可用产物。

原脚本把 `dependency:resolve` 单独调用（没有 `compile`），Maven 只能转去本地仓库找 `com.wingtisky:wt-common`，找不到就失败。

**为什么本地一直没暴露**：本机仓库里装着这些 SNAPSHOT（早先 `mvn install` 的残留）。**这个"检查"实际上一直在靠本机残留状态才绿**——它验证的不是依赖真实性，而是"我的机器上恰好装过"。

**解决**：把 `compile` 与 `resolve` 放进**同一次 Maven 调用**：

```bash
mvn -q -B compile dependency:resolve dependency:resolve-plugins
```

**复现与验证手法**（这个比结论更值得记）：把本地仓库里的 `com/wingtisky` 暂时挪走，就**在本地复现出了 CI 的失败**；试出修法后，再在同样状态下验证通过，最后还原。

**教训**：
1. **凡是"调用 `mvn` 做校验"的脚本，都要问一句：它依赖本机仓库的残留状态吗？** 本地绿不代表干净环境绿。
2. **这正是 CI 存在的意义**。`check-deps.sh` 在 Task 6 写完后本地跑了两次都是绿的，看起来完全正常——它的问题只有在空仓库里才现形。

**顺带发现的一处本地残留**：本机仓库 `com/wingtisky/` 下还留着**旧架构的模块**（`forum-module-user` / `forum-module-content` / `forum-infra` 等，来自已删除的 9 模块骨架）。它们不影响构建（新 POM 不再引用这些坐标），但是**陈旧状态**——留着会让人误以为还有这些模块。

**耗时**：约 15 分钟（含本地复现）

---

## 2026-09-29 · 建表时中文被写坏（mysql 客户端默认 GBK）

**现象**：建表脚本执行成功、退出码 0，但数据是坏的——

```
MODERATOR  版主  →  9 字节（应为 6 字节），乱码
USER       普通用户 →  hex 里出现 3F 3F，也就是两个问号
```

**根因**：Windows 上 `mysql` 客户端的 `character_set_client` 默认是 **gbk**，而 `.sql` 文件是 **UTF-8**。客户端把 UTF-8 字节按 GBK 解释，转不过去的字符被替换成 `?`。

**关键点：`?` 是不可逆的**——不是显示问题，是数据真的坏了。而且**脚本退出码是 0，没有任何报错**。

**解决**：客户端加 `--default-character-set=utf8mb4`：

```bash
mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum < db/V1__init_user.sql
```

**验证方式**（不要靠肉眼看终端，会骗你）——查真实字节：

```sql
SELECT code, name, HEX(name), CHAR_LENGTH(name) AS chars, LENGTH(name) AS bytes FROM t_role;
-- 版主 → E78988E4B8BB / 2 字 / 6 字节（每个汉字 3 字节）
```

**耗时**：约 10 分钟

---

## 2026-09-29 · 应用启动失败：Unsupported character encoding 'utf8mb4'

**现象**：应用启动时 HikariCP 建连失败：

```
Caused by: java.io.UnsupportedEncodingException: utf8mb4
Caused by: com.mysql.cj.exceptions.WrongArgumentException: Unsupported character encoding 'utf8mb4'
```

**根因**：JDBC URL 里的 `characterEncoding` 要的是 **Java 的字符集名**，而 `utf8mb4` 是 **MySQL 的**叫法。两者不是一回事。

**解决**：写 `characterEncoding=UTF-8`。Connector/J 8+ 收到 UTF-8 后会自行协商成 MySQL 侧的 utf8mb4，**不需要也不应该**写 utf8mb4。

> 顺带说明：这与上一条是**两个不同层面**的编码问题——上一条是客户端 ↔ 服务端，这一条是 JDBC 驱动的参数解析。同一个"utf8mb4"，在两个地方的含义不同。

---

## 2026-09-29 · Maven `provided` 作用域的前提是"运行时真的有人提供"

**现象**：编译通过、单元测试全绿，但应用一启动就崩：

```
Caused by: java.lang.NoClassDefFoundError: org/springframework/security/access/AccessDeniedException
  → Failed to introspect Class [GlobalExceptionHandler]
```

**根因**：把 `spring-security-core` 标成了 `provided`，意思是"编译需要、运行时由别人提供"。**但当时没人提供它**——Spring Security 要到 Task 4 才引入。于是运行时的类加载器找不到那个类，组件扫描在反射 `GlobalExceptionHandler` 的方法签名时直接失败。

**为什么编译期和单测都发现不了**：编译只需要 API 在 classpath 上（provided 提供）；单测用的是 MockMvc standalone，**根本不扫组件**。只有**真启动一次**才暴露。

**解决**：改成 `compile` 作用域。

**教训**：`provided` 不是"我觉得运行时会有"，而是"**我确认运行时有**"。
- `spring-boot-starter-web` 标 provided 成立——`app` 模块自己引了它
- `spring-security-core` 标 provided 不成立——当时无人提供

**这也解释了项目为什么把"接入后第一关是启动验证"写进 spec §9 闸门 5**：编译能过、测试能过、启动崩掉，是三个独立的关卡。

---

## 2026-09-29 · `.env` 里的 `&` 不加引号会被 shell 吃掉

**现象**：JDBC URL 明明写在 `.env` 里，应用却拿到了空值（用了默认值，而默认值里恰好有另一个 bug，于是排查方向一度被带偏）。

**根因**：`.env` 里的值若不加引号，`set -a && source .env` 会把 `&` 当成 shell 的**后台运算符**：

```bash
DB_URL=jdbc:mysql://...?useSSL=false&allowPublicKeyRetrieval=true
#                                     ↑ 这里被当成 & 运算符
```

**解决**：含特殊字符的值一律加引号。

```bash
DB_URL="jdbc:mysql://...?useSSL=false&allowPublicKeyRetrieval=true&..."
```

**验证**：`set -a; . ./.env; set +a; echo ${#DB_URL}` → 应输出完整长度（本项目为 138），空值或过短说明被截断了。

---

## 2026-09-29 · Maven 的 `-D` 覆盖不动 POM 里写死的字面量

**现象**：想临时跑被排除的测试，命令行传了 `-DexcludedGroups=__none__`，
结果**一个测试都没跑，却显示 `BUILD SUCCESS`**——没有任何报错提示"你的参数没生效"。

**根因**：Maven 的用户属性（`-D`）**只在 POM 用 `${...}` 表达式时才覆盖得动**。
POM 里写死 `<excludedGroups>integration</excludedGroups>` 时，这个字面量赢，
命令行参数被静默忽略。

**解决**：把值抽成属性，POM 里写表达式：

```xml
<properties>
    <excluded.groups>integration</excluded.groups>
</properties>
...
<configuration>
    <excludedGroups>${excluded.groups}</excludedGroups>
</configuration>
```

之后 `-Dexcluded.groups=__none__` 才生效。

**为什么这条危险**：它的失败模式是**静默的成功**——构建绿、没有报错、
你以为测试跑了。同类问题还包括"以为改了配置，其实没改"。
**判断依据是"测试数对不对"，不是"构建绿不绿"。**

---

## 2026-09-29 · `-am` 缺失导致的"本地仓库陈旧"第三次咬人

**现象**：集成测试一跑就 `NoClassDefFoundError: com/wingtisky/forum/common/result/ErrorCode`——
而那个类明明就在 `wt-common` 里，且刚刚编译通过。

**根因**：`mvn -pl app test` **没带 `-am`**。这种情况下 Maven 不从 reactor 构建上游模块，
而是去**本地仓库**找 `wt-common:1.0.0-SNAPSHOT`——而本地仓库里装的是**改动之前的旧版本**，
里面没有 `ErrorCode`。

**这是同一个根因的第三次**：
1. CI 首跑时 `check-deps.sh` 失败（内部模块坐标只由 reactor 提供）
2. `mvn -pl app dependency:tree` 显示的是旧版 wt-infra 的依赖树
3. 本次

**已经固化成一条规则**：**凡是对单个模块执行 `-pl <module>`，一律同时带上 `-am`。**
不是"需要时才加"——本地仓库里有旧版本时，不加就会静默地用错东西。
