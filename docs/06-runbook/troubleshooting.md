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
