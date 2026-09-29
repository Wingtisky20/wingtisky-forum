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
