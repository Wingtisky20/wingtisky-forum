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

---

## 2026-09-29 · 日志里的中文 grep 不到（平台默认编码是 GBK）

**现象**：验证限流降级时，日志里明明打了 15 条 `ERROR 限流降级：Redis 不可用`，
但 `grep '限流降级' /tmp/app.log` 返回 **0 条**。一度被误导成"降级分支根本没执行"，
差点去改一个本来正确的实现。

**根因**：Java 17 在中文 Windows 上，控制台输出按**平台默认编码**（GBK）写。
而排查时用的是 UTF-8 的匹配模式，两边对不上。

**这条的危害不是"日志有乱码"，而是"它让人以为某段代码没执行"**——
一个正在生效的降级机制看起来像没生效，排查方向直接被带偏。

**解决**：

```yaml
logging:
  charset:
    console: UTF-8
```

交互式终端若因此显示乱码，执行 `chcp 65001` 切到 UTF-8 代码页。

**验证**：修复前 `grep -c '限流降级'` → 0；修复后重启触发限流，
`grep -o '触发限流[^,]*'` → `触发限流: path=/api/auth/login`。

**快速判断当前日志是什么编码**（不改配置时的临时手段）：

```bash
grep "某个英文类名" app.log | od -c | head    # 看中文那几个字节是 0xB4 开头(GBK)还是 0xE4 开头(UTF-8)
```

**耗时**：约 15 分钟（其中大半花在怀疑"代码是不是没跑到"上）

---

## 2026-10-05 · `curl` 发带中文的 JSON 一律失败（命令行编码还是 GBK）

**现象**：验证 M2 的帖子接口时，注册用户一直返回
`{"code":"A0001","message":"请求体格式不正确"}`（HTTP 400）。
**第一反应是"接口写错了"或者"参数校验太严"**——但把中文昵称换成纯 ASCII，立刻成功。

**根因**：与上面那条日志编码**同一个根源**——中文 Windows 的控制台默认 GBK。
写进命令行的中文以 GBK 字节发出去，服务端按 UTF-8 解析，得到非法字节序列，
Jackson 直接判定"请求体格式不正确"。**服务端没问题，是发出去的字节本身就不对。**

**这条最坑的地方**：它伪装成"接口有 bug"。400 的文案指向"请求体格式"，
而真正的问题在调用方。如果没做那个"ASCII 成功 / 中文失败"的对照实验，
很容易跑去改一个本来正确的接口——**改动方向完全错，而且是往正确的地方加补丁**。

**解决**：带中文的请求体**写进文件再发**，不要内联在命令行里。
文件由编辑器写成 UTF-8，`--data-binary @文件` 原样发送：

```bash
# 错的：中文经过命令行 → GBK → 服务端 400
curl -X POST $BASE/api/posts -H 'Content-Type: application/json' \
     -d '{"title":"中文标题","content":"正文"}'

# 对的：payload.json 是 UTF-8 文件，内容与上面完全相同
curl -X POST $BASE/api/posts -H 'Content-Type: application/json' \
     --data-binary @payload.json
```

**验证**：同一份 JSON，两个发法对照——

| 发法 | 结果 |
|---|---|
| `-d '{…"nickname":"探测用户丙"}'` | **HTTP 400** `A0001` 请求体格式不正确 |
| `--data-binary @payload.json`（内容完全相同） | **HTTP 200**，列表里读回来的中文正常 |

**顺带确认了一件事**：应用本身对 UTF-8 中文的处理是**正常**的。
这条坑属于**本地验证环境**，不属于应用。M1 的 `api-smoke.http` 里带中文的请求
从 IDEA 发出去一直是好的，原因就在这里——IDEA 用 UTF-8 发。

**耗时**：约 10 分钟

---

## 2026-10-06 · 151 个单元测试只跑了 56 个，而构建显示 `BUILD SUCCESS`

**现象**：`mvn -B test` 干净地打印 `BUILD SUCCESS`。但项目实际有 **151** 个单元测试，
真正执行的只有 **56** 个。被跳过的测试类在报告里写着 `Tests run: 0`，
读起来像"这个类里没有测试"，**不报任何错**。

被跳过的正好是最要紧的那块：`forum-content` 的 94 个测试（M3 要改的内容域）
一个都没跑，`forum-user` 也少跑了 15 个。

**怎么发现的**：M3 Task 1 的验证要求核对测试数（计划里定的基线是 151）。
如果那条要求写成"看到 BUILD SUCCESS 即可"，这个坑会被一路带下去。

**根因**：surefire 默认 `useManifestOnlyJar=true`——为了不把命令行撑爆，
它把"要用哪些依赖"写成一个**临时 manifest JAR** 交给 fork 出的测试进程。
该文件落在系统临时目录，本机是 `C:\Users\w\AppData\Local\Temp`，
而**项目与 Maven 仓库都在 D 盘**。跨盘符时这份清单生成不出来，
surefire 自己的 dump 文件里留着这句话：

```
Boot Manifest-JAR contains absolute paths in classpath ...
'other' has different root
```

于是 fork 进程拿到的 classpath 是断的 → JUnit 在**发现阶段**就抛
`TestEngine with ID 'junit-jupiter' failed to discover tests` →
surefire 把"发现失败"记成"这个类没有测试" → **构建照常成功**。

**这不是新坑**：`target/surefire-reports/` 下的 dump 文件日期是 **2026-10-05**
（M2 期间），说明它已经存在了一段时间。

**解决**：父 `pom.xml` 的 surefire 配置里关掉它。

```xml
<useManifestOnlyJar>false</useManifestOnlyJar>
```

改完实测：`mvn -B clean test` → **151 个全部执行**，`BUILD SUCCESS`
（wt-common 11 + forum-user 41 + forum-content 94 + app 5）。

**代价**：classpath 改用命令行传递。Windows 命令行有长度上限（约 32K 字符），
依赖极多时可能反过来报"命令行过长"。本项目远未触及，Linux/CI 不受影响。

**将来若以别的面貌复现，这样定位**：跑完测试后，看报告里**有没有 `Tests run: 0` 的类**；
有，就去该模块的 `target/surefire-reports/` 翻 `*.dumpstream`。

> ### 排查过程中我走错的一步（留着，因为它更容易犯）
>
> 我先用 `mvn -B test -pl forum/forum-content -Dtest=PostServiceTest` 单独跑一个模块，
> 报出 `NoClassDefFoundError: com/wingtisky/forum/common/exception/BizException`，
> 一度以为找到了根因。
>
> 其实那只是**上面 2026-09-29 那条 `-am` 的坑**：`-pl` 不带 `-am` 时，
> Maven 去本地仓库取上游模块，拿到的是旧版本。**同一份文档里早就写过这条。**
>
> 教训：**单模块复现出来的错误，不能直接当根因**——先确认它是不是构建方式造成的假象。
> 换完整 reactor 重跑，那条 `NoClassDefFoundError` 就消失了。

**为什么这条值得记**：它的失败模式是**静默的成功**——构建绿、无报错、
测试报告文件也在（只是写着 0）。这和 2026-09-29「`-D` 覆盖不动 POM 字面量」那条
是同一类：**判断依据必须是"数对不对"，不是"绿不绿"。**

**还没做的一件事**：给构建加一道**测试数下限断言**，让"静默少跑"直接变成构建失败。
本期没做（它属于构建逻辑，想和 M10 的工具链整理一起考虑）。

---

## 2026-10-06 · 类里多一个构造器，主构造器就必须标 `@Autowired`

**现象**：应用/集成测试启动时炸，报

```
Failed to instantiate [com.wingtisky.forum.infra.cache.TwoLevelCache]: No default constructor found
Caused by: java.lang.NoSuchMethodException: ...TwoLevelCache.<init>()
```

**根因**：这个类有**两个**构造器——一个主构造器（给 Spring 注入依赖用）
和一个给测试用的（多传一个可控参数）。**当一个类有多个构造器、又没有哪个标了
`@Autowired` 时，Spring 不再猜**，而是直接去找无参构造器，找不到就报上面这个错。

**解决**：给主构造器标 `@Autowired`。

**为什么这条特别阴险**：

| 它的表现 | 后果 |
|---|---|
| **单测全绿** | 单测自己 `new` 对象，压根不建 Spring 上下文——错在哪它看不见 |
| 只在**启动 / 集成测试**时才炸 | 而这两个回馈最慢 |
| 报错信息指向"缺少无参构造器" | 和"少标了注解"隔着一步，第一次见要愣一下 |

**这条在本项目踩了两次**：`TwoLevelCache`（M3 Task 2）、`CacheEvictionBroadcaster`
（M3 Task 4）。第二次踩的时候第一次的注释就在旁边。

> **所以定成规矩，不靠记性**：
> **类里只要多出一个构造器（通常是给测试用的），主构造器就标 `@Autowired`。**
>
> 更省事的做法是别让测试需要第二个构造器——但那有时做不到
> （比如要注入一个生产环境不该暴露的参数）。做不到时，就标注解。
