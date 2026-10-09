# 本地环境搭建

> 用途：让一个陌生人在**干净机器**上也能把这套环境跑起来；也记录"本地开发配置
> 与生产环境的差异"——后者是面试时的加分项，不是可选项。
>
> 配套：`troubleshooting.md`（踩过的坑）、`scripts/`（启停脚本）。
> 最后更新：2026-10-07（M3 收尾：Redis 从"可选"变"必须"、浏览数改成最终一致）

---

## 1. 前置要求

| 组件 | 版本 | 位置 |
|---|---|---|
| JDK | **17.0.16** LTS | `D:\aaaSoftware\jdk\jdk-17` |
| Maven | **3.9.14** | `D:\aaaSoftware\Maven\apache-maven-3.9.14` |
| Node | **v24.14.0**（npm 11.9.0） | `D:\aaaSoftware\nodejs` |
| MySQL 客户端 | 8.0.41 | `C:\Program Files\MySQL\MySQL Server 8.0` |

**硬件**：16 GiB 内存 · Ryzen 5 4500U（6 核）· Win10 Home · **无 Docker、无 WSL2**。

> 这台机器上**所有中间件与工具都装在 D 盘**。C 盘空间紧张，装东西前先看一眼。

---

## 2. 中间件清单与端口

| 中间件 | 版本 | 端口 | 何时需要 | 状态 |
|---|---|---|---|---|
| MySQL | 8.0.41 | 3306 | 全程 | 沿用（早已装好 · 常驻运行）**Windows 原生，不动** |
| Redis | 5.0.14.1 | 6379 | **M1 起** | 沿用（**M3 起是启动期硬依赖**）**Windows 原生，不动** |
| Kafka | 3.9.1（KRaft） | 9092 | M4 起 | ✅ **可用**——跑在 **WSL2 的 Docker 容器**里；见 §6 |
| Elasticsearch | 8.18.3 + IK | 9200 / 9300 | M5 起 | 未起；**M5 也走 Docker**（同一个 compose 文件） |

**按需启动**（architecture.md §6.2）：做内容域时只起 MySQL + Redis 就够，
不必四个全开。

> **中间件分两处承载**（2026-10-08 定，决策见 [ADR-0021](../02-decisions/ADR-0021-docker-in-wsl2.md)）：
> **MySQL 与 Redis 在 Windows 原生；Kafka 与 ES 在 WSL2 的 Docker 容器里。**
> 判据是"动它值不值"——只动了坏掉的那个（Kafka）和跟它同类的那个（ES）。
> Windows 侧应用**照旧连 `localhost:9092` / `localhost:9200`**，配置不用改。

> **WSL2 已装**（2026-10-08）：Ubuntu-22.04，虚拟磁盘在
> `D:\aaaSoftware\wsl\Ubuntu-22.04\ext4.vhdx`（**在 D 盘**，所以 WSL2 里装的一切都落 D 盘）。
> 内存上限 6 GB，配置在 `C:\Users\w\.wslconfig`。
>
> ⚠️ **起 Kafka 的窗口要一直开着** —— WSL2 空闲约 1 分钟会自动关机，容器跟着死。
> 详见 §6.4。

> **旧版本保留未删**：`elasticsearch-6.8.23` 与 `kafka`（2.8.1，带 ZooKeeper）
> 仍在 `D:\aaaSoftware` 下，属于上一个项目。**不要覆盖它们**——一是旧项目可能
> 还要跑，二是 ES 8 若在本机装不通，降级路径需要它们（见 ADR-0001）。

---

## 3. MySQL 8.0

已作为 Windows 服务常驻，无需额外操作。

```bash
mysql --version          # → Ver 8.0.41 for Win64 on x86_64
netstat -ano | grep ':3306 ' | grep LISTENING   # → 有输出即在跑
```

### 3.1 建库与建账号（**一次性步骤，需要 root**）

**不要用 root 跑应用**——给项目一个只能碰自己那两个库的账号：

```sql
CREATE DATABASE IF NOT EXISTS wingtisky_forum      DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS wingtisky_forum_test DEFAULT CHARACTER SET utf8mb4;

CREATE USER IF NOT EXISTS 'wingtisky'@'localhost' IDENTIFIED BY '换成你自己的密码';
GRANT ALL PRIVILEGES ON wingtisky_forum.*      TO 'wingtisky'@'localhost';
GRANT ALL PRIVILEGES ON wingtisky_forum_test.* TO 'wingtisky'@'localhost';
FLUSH PRIVILEGES;
```

验证最小授权——应当**只有**这两个库，没有 `*.*` 的通配权限：

```bash
mysql -u root -p -e "SHOW GRANTS FOR 'wingtisky'@'localhost';"
```

**本项目约定**：集成测试连**独立的 test 库**，不污染开发数据（ADR-0007）。

### 3.2 建表

```bash
# V1：用户域（M1）
mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum      < db/V1__init_user.sql
mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum_test < db/V1__init_user.sql

# V2：内容域（M2）—— 帖子 / 评论 / 标签 / 点赞 / 收藏 / 帖子标签
mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum      < db/V2__init_content.sql
mysql --default-character-set=utf8mb4 -u wingtisky -p wingtisky_forum_test < db/V2__init_content.sql
```

> **⚠️ 2026-10-07 补：上面原来只列了 `V1__`。** M2 加了 `V2__init_content.sql`，
> 却没回头更新这一节——照着旧版本做，**应用能起来，但一碰内容域就报"表不存在"**。
> 这正是本文档被 `troubleshooting.md` 反复提醒的那类问题：**漏一步不报错，只在下游炸**。
> 以后每加一个 `V*__` 脚本，回来加一行。

> ⚠️ **`V1__` 这种命名看起来像 Flyway，但本项目没有引入 Flyway**（它不在技术栈白名单里）。
> 文件只是按那个约定命名，**靠手工执行**。后续里程碑新增表时沿用 `V3__`、`V4__` 即可，
> 别忘了**开发库和测试库都要执行**——只对开发库执行的话，集成测试会因为缺表而失败，
> 而那个报错信息（"表不存在"）不会提示你去跑脚本。

> ⚠️ **`--default-character-set=utf8mb4` 不能省。** Windows 上 mysql 客户端的默认字符集是
> **gbk**，而脚本文件是 UTF-8。不加这个参数时：**脚本执行成功、退出码 0、没有任何报错**，
> 但中文会被写坏（转不过去的字符变成 `?`，**不可恢复**）。
> 详见 `troubleshooting.md` 同日条目。

**验证要查字节，不要肉眼看终端**（终端编码会骗你）：

```sql
SELECT code, name, HEX(name), CHAR_LENGTH(name) AS chars, LENGTH(name) AS bytes FROM t_role;
-- 期望：版主 → E78988E4B8BB / 2 字 / 6 字节（每个汉字 3 字节）
```

### 3.3 凭据怎么给应用

复制 `.env.example` 为 `.env` 并填值（`.env` 已在 `.gitignore` 里，**绝不入库**）：

```bash
cp .env.example .env
```

> ⚠️ `.env` 里含 `&` 的值**必须加引号**，否则 `source .env` 会把 `&` 当后台运算符，
> 变量只被赋值到第一个 `&` 之前（实测结果是空值）。

---

## 4. Redis 5.0.14.1（沿用）

```bash
cd D:/aaaSoftware/redis
./redis-server.exe &                      # 启动（默认 6379）
./redis-cli.exe PING                      # → PONG
./redis-cli.exe INFO server | grep redis_version   # → redis_version:5.0.14.1
```

**为什么停在 5.0.14.1**：这是 **Windows 原生构建的天花板**，更高版本没有官方
Windows 版，而本机无 Docker/WSL2（ADR-0004）。这是环境锁死的约束，不是偏好。

**连带风险**：Redisson 3.52.0 可能用到 Redis 6+ 才有的命令。

> **✅ 2026-10-06 实测：兼容。** 加锁 / 释放 / 看门狗参数在 5.0.14.1 上都正常，
> 没有 `ERR unknown command`。**无需降级、无需修订 ADR-0001**，
> 挂了一阵的待核实项就此关闭。守着它的测试是 `RedissonSmokeIntegrationTest`。

---

**⚠️ M3 起，Redis 是「必须起着」的（不再是"可选"）**

在 M1/M2 时，Redis 只被认证与限流用到，所以本机不起 Redis 也能把应用跑起来
（不碰那两个功能就行）。**M3 之后这条不再成立**：

- 两极缓存（亮点 1）的 L2、以及回源用的分布式锁都在 Redis 上；
- 更直接的：Redisson 的自动配置**在启动时就要连上**，连不上应用直接起不来：

```
Failed to instantiate [org.redisson.api.RedissonClient]:
  RedisConnectionException: Unable to connect to Redis server: 127.0.0.1:6379
```

这是**有意接受的行为变更**（理由记在 `app/src/main/resources/application.yml`
的 Redis 段注释里：M3 起没有 Redis 应用本来就干不了活，
保住"能启动"要多一条锁降级路径，是新的失效面）。

**所以：跑应用之前，先 `redis-cli PING` 确认它活着。**

### 4.1 排查数据时要知道的一件事：浏览数是**最终一致**的

**M3 起，`t_post.view_count` 不是每次被人看就立刻更新。** 读详情时浏览数只涨在 Redis
计数器（`wt:cache:post:view:<id>`）里，**每 30 秒**才回写一次数据库。

所以下面这种情况**不是 bug**：

- 刚读了某篇帖子，**去库里查 `view_count` 还是旧值** → 等一次回写就到了；
- 反过来，**Redis 重启会丢掉最近一段没回写的增量** → 这是有意接受的代价
  （见 `PostViewCounter` 的类注释，以及 `architecture.md` §2.1：
  这个域的一致性要求就是"最终一致可接受（浏览数）"）。

想立刻核对，用这两条：

```bash
redis-cli GET "wt:cache:post:view:<id>"              # Redis 里的当前值
redis-cli SISMEMBER "wt:cache:post:view:dirty" "<id>" # 1 = 还没回写
```

> **它换来的是**：读详情这条路上**一次数据库写都没有**了。M2 时每看一次就要
> `UPDATE t_post SET view_count = view_count + 1`——那恰恰是全站最频繁的写。

---

## 5. Elasticsearch 8.18.3 + IK（本次新装）

### 5.1 安装

```bash
cd D:/aaaSoftware/_downloads
curl -LO "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-8.18.3-windows-x86_64.zip"
unzip -q elasticsearch-8.18.3-windows-x86_64.zip -d D:/aaaSoftware/
```

### 5.2 配置（`config/elasticsearch.yml` 追加）

```yaml
# ---- 本地开发配置（生产环境必须开启安全，见 §9）----
cluster.name: wingtisky-forum
node.name: node-1
discovery.type: single-node
xpack.security.enabled: false
xpack.security.enrollment.enabled: false
http.port: 9200
network.host: 127.0.0.1
```

`config/jvm.options` 把堆从默认改成固定 1G（ES 默认按物理内存一半算堆，
16G 机器上会直接吃掉 8G）：

```
-Xms1g
-Xmx1g
```

> 堆限制同时写进了 `scripts/start-es.bat` 的 `ES_JAVA_OPTS`——**两处保持一致**，
> 脚本里那份是实际生效的入口。

### 5.3 安装 IK 分词器

```bash
cd D:/aaaSoftware/elasticsearch-8.18.3/bin
./elasticsearch-plugin.bat install --batch "https://get.infini.cloud/elasticsearch/analysis-ik/8.18.3"
```

> ⚠️ **ES 8.18 起插件安全机制变了**：ES 会用 Entitlements 取代旧的
> SecurityManager，而 IK 目前仍是旧的 Security Policy 格式，安装时会打印一段
> 警告并要求 `outbound_network` 权限。**实测可正常安装并使用**，但这个警告值得
> 留意——将来升级 ES 时它可能变成硬性失败。

### 5.4 启动与验证

```bash
cmd /c scripts\start-es.bat          # 用脚本启动（堆限制在脚本里）
curl -s http://localhost:9200        # → cluster_name: wingtisky-forum
```

**IK 是否真的生效**（关键验证，别只看端口通不通）：

```bash
curl -s "http://localhost:9200/_analyze" -H 'Content-Type: application/json' \
  --data-binary '{"analyzer":"ik_max_word","text":"多级缓存与Redisson分布式锁"}'
```

**期望**：得到 `多级` / `缓存` / `redis` / `分布式` / `锁` 这类**词**。

**若切成单字（多/级/缓/存），说明 IK 没生效**——ES 的默认 standard 分析器对
中文就是切单字，两者的输出差别非常明显。

> **命令行传中文 JSON 会踩编码坑**（Git Bash 下 `-d '{"text":"中文"}'` 会报
> `x_content_parse_exception`）。稳妥做法是把 JSON 写进文件再
> `--data-binary @file`。

---

## 6. Kafka 3.9.1（KRaft 模式）—— 跑在 WSL2 的 Docker 容器里

> ### 先读这一节。下面 §6.1 起是**已废弃**的原生 Windows 装法，保留备查。
>
> **怎么用（日常只要这两条）**：
>
> ```bash
> scripts\start-kafka.bat     # 启动（⚠️ 这个窗口要一直开着，关了 Kafka 就停）
> scripts\stop-kafka.bat      # 停止（保留数据）
> ```
>
> **配置文件在仓库里**：`deploy/docker-compose.yml`。
> 要改 Kafka 的参数，改那个文件，然后重跑 `start-kafka.bat`。
> **要连数据一起清空重来**（相当于换个全新环境）：
>
> ```bash
> # 在 WSL2 里执行
> docker compose -f /mnt/d/aaaDocuments/project/WingtiskyForum/deploy/docker-compose.yml down -v
> ```
>
> ### 为什么是 Docker（而不是原生、也不是手工装）
>
> **原生 Windows 走不通**：Kafka broker **凡需要回收磁盘就会自杀**——
> 删日志段、删主题、日志压缩清理，任意一个触发即崩（2026-10-07 实测 **16 次**，
> 报 `另一个程序正在使用此文件` 后 `Shutdown broker because all log dirs failed`）。
> 排除实验做了九轮：与数据新旧、目录新旧、杀毒软件、保留期长短、清理器开关**都无关**；
> 唯一相关的是"这一次运行中有东西要删"。
>
> **WSL2 里手工装也不选**：装成了、也能用，但为了让它跑起来我手工改过
> DNS、IPv6、listener、数据目录、heap——**没有一处写进可提交的文件**，换台机器就得重来。
>
> **所以选了 Docker**：配置变成一份**能进仓库、能审、能 diff** 的文件，
> 重置环境是 `down -v` 一句话。
>
> 完整决策：[ADR-0020](../02-decisions/ADR-0020-wsl2-for-kafka.md)（离开原生）、
> [ADR-0021](../02-decisions/ADR-0021-docker-in-wsl2.md)（改用 Docker，含三个被否决方案）。
> 排查过程（含五个说错过的结论）见 [troubleshooting.md](troubleshooting.md)。
>
> ### 6.4 起是三条**必须知道**的注意事项（窗口要开着、端口别绑回环、内存账），
> 遇到问题先看那里。

### 6.1 下载与解压（**已废弃，保留备查**）

> ⚠️ 以下步骤**不要照做**。它记录的是原生 Windows 的安装过程，
> 那条路已被证伪（见上）。保留它是因为**下载与格式化的知识对理解 KRaft 仍然有用**，
> 而且如果将来要在别的 Linux 环境里手工装，这些步骤可以直接参考。
>
> **Kafka 3.9.x 已经是归档版本，国内镜像全都没有。** 实测清华 / 阿里 / 中科大 /
> 南大 / 网易 / 华为云均只保留当前发布版（4.1~4.3），3.9.x 一律 404，只能从
> `archive.apache.org` 取。**直连约 8 KB/s、走代理一开始约 16 KB/s**，但速度会
> 中途提上来——本次实测**总耗时约 20 分钟**（走了代理 + 断点续传 + 重试）。
>
> **关键：它是后台下载，不该阻塞其他工作。** 正确的顺序是——Kafka 丢后台，
> **同时去装 ES**。两个中间件本来就没有依赖关系，串行等纯属浪费。

```bash
cd D:/aaaSoftware/_downloads
# 走代理并支持断点续传（Clash 混合端口，见 troubleshooting.md）
curl -x http://127.0.0.1:18569 -L -C - --retry 20 --retry-all-errors \
  -o kafka_2.13-3.9.1.tgz \
  "https://archive.apache.org/dist/kafka/3.9.1/kafka_2.13-3.9.1.tgz"

mkdir -p D:/aaaSoftware/kafka-3.9.1
tar -xzf kafka_2.13-3.9.1.tgz -C D:/aaaSoftware/kafka-3.9.1 --strip-components=1
```

### 6.2 配置（`config/kraft/server.properties`）

> ⚠️ **配置必须排在格式化之前。** 格式化会**按当时生效的配置**落盘：默认配置是
> `log.dirs=/tmp/kraft-combined-logs`，在 Windows 上会被解析成**当前盘根目录**下的
> `tmp`（实测落到了 `D:\tmp\kraft-combined-logs`）。顺序颠倒就要返工重来——
> 本次就是这么踩的，详见 `troubleshooting.md`。

```properties
log.dirs=D:/aaaSoftware/kafka-3.9.1/kraft-logs
listeners=PLAINTEXT://127.0.0.1:9092,CONTROLLER://127.0.0.1:9093
advertised.listeners=PLAINTEXT://127.0.0.1:9092
controller.quorum.voters=1@127.0.0.1:9093
num.partitions=3
```

`controller.quorum.voters` 默认写的是 `localhost:9093`，这里一并改成 `127.0.0.1`
——**全用同一个地址**，避免监听地址是 IP、仲裁地址是主机名这种混搭带来的解析问题。

### 6.3 生成集群 ID 并格式化（**一次性步骤**）

KRaft 模式不需要 ZooKeeper，但需要先给存储目录做一次格式化——**这一步只做一次**，
漏了会报错且报错信息不直观，所以单独列出来。

```bash
cd D:/aaaSoftware/kafka-3.9.1
bin/windows/kafka-storage.bat random-uuid            # 生成集群 ID，记下来
bin/windows/kafka-storage.bat format -t <集群ID> -c config/kraft/server.properties
```

**期望输出**：`Formatting metadata directory D:/aaaSoftware/kafka-3.9.1/kraft-logs` ——
**确认路径是你要的那个**，不是 `/tmp/...`。格式化后该目录下会出现
`meta.properties` 与 `bootstrap.checkpoint`。

注意两个端口的分工：**9092 是 broker，9093 是 controller**。KRaft 把元数据管理
收进了 broker 自己，controller 端口就是原来的 ZooKeeper 干的活。

### 6.4 启动与验证

```bash
cmd /c scripts\start-kafka.bat

# 建一个冒烟 topic 并查看
cd D:/aaaSoftware/kafka-3.9.1
bin/windows/kafka-topics.bat --bootstrap-server 127.0.0.1:9092 \
  --create --topic smoke-test --partitions 3 --replication-factor 1
bin/windows/kafka-topics.bat --bootstrap-server 127.0.0.1:9092 \
  --describe --topic smoke-test
```

**期望**：`describe` 显示 **3 个分区**，且每个分区的 `Leader` 都不是 `none`：

```
Topic: smoke-test  PartitionCount: 3  ReplicationFactor: 1
  Partition: 0  Leader: 1  Replicas: 1  Isr: 1
  Partition: 1  Leader: 1  Replicas: 1  Isr: 1
  Partition: 2  Leader: 1  Replicas: 1  Isr: 1
```

**再验一次真的能收发**（只建 topic 说明不了 broker 能干活）：

```bash
echo "hello-kraft-smoke-test" | bin/windows/kafka-console-producer.bat \
  --bootstrap-server 127.0.0.1:9092 --topic smoke-test
bin/windows/kafka-console-consumer.bat --bootstrap-server 127.0.0.1:9092 \
  --topic smoke-test --from-beginning --max-messages 1
```

**期望**：消费端原样打印 `hello-kraft-smoke-test`。

**关键验证点——确认没有 ZooKeeper 进程**（这是 KRaft 的核心收益）：

```bash
tasklist | grep -i zookeeper      # → 无输出
tasklist | grep -i java           # → 有 Kafka broker
```

---

## 7. 一键启停

### 7.1 Kafka（WSL2 + Docker）

| 脚本 | 作用 |
|---|---|
| `scripts/start-kafka.bat` | 启动 Kafka 容器，**然后前台挂住不退出** |
| `scripts/stop-kafka.bat` | 停止容器（**保留数据**） |

**⚠️ start-kafka.bat 的那个窗口要一直开着。** 关掉它 = 停掉 Kafka。
这不是偷懒，是**必须**——见 §7.3。

**要清空数据重来**（相当于换一个全新环境）：

```bash
# 在 WSL2 里执行
docker compose -f /mnt/d/aaaDocuments/project/WingtiskyForum/deploy/docker-compose.yml down -v
```

### 7.2 ES（原生，M5 之前会一并挪进 Docker）

| 脚本 | 作用 |
|---|---|
| `scripts/start-es.bat` | 启动 ES（堆固定 1G，启动前切到安装目录） |

**停止**（原生那套）：按端口找 PID 再精确 kill，**不要用 `taskkill /IM java.exe`**——
那会连带杀掉 IDEA 等所有 java 进程。

```bash
PID=$(netstat -ano | grep ':9200 ' | grep LISTENING | awk '{print $5}' | head -1)
taskkill //PID "$PID" //F
```

### 7.3 三条必须知道的注意事项

**① 窗口要开着 —— WSL2 空闲约 1 分钟会自己关机**

不敲 WSL 命令约 1~2 分钟后，**整台虚拟机自动关掉**，容器跟着死，`localhost:9092` 就没了。
表现是"**时好时坏**"：刚验证完是通的，过两分钟再试就不通。

`vmIdleTimeout` 这个设置在本机 WSL（3.0.1.0）上**无效**，所以靠前台会话撑住。
**实测**：挂住后 5 分钟 15 次探测全通；不挂时 12 次全断。

> **定位手法**：这类"看着像随机故障"的问题，先去看系统日志有没有重启标记
> （`journalctl -u docker`，看到 `Daemon shutdown complete` 后面跟着 `-- Boot --`
> 就是整台虚拟机重启，**不是容器重启**）。详见 `troubleshooting.md`。

**② compose 里的端口别绑 `127.0.0.1`**

写成 `"127.0.0.1:9092:9092"` 时，**Windows 侧连不进来**——
因为 WSL2 的 localhost 转发不接管只绑回环的端口。写成 `"9092:9092"` 才对。
（写成回环时，Kafka 客户端报的是 `Timed out waiting for a node assignment`，
**看着像配置问题，其实是连不上**。）

**③ 内存账变了，M9 之前必须重算**

WSL2 内存上限由 4 GB 抬到 **6 GB**（容器本身约 0.5 G 开销）。

```
MySQL(0.5) + Redis(0.05) + WSL2(6.0) + Windows/IDE/浏览器(4.5) ≈ 11 G / 16 G
```

**M5**（Kafka + ES 都开）时约 11 G，**能撑住**；
但 **M9** 还要再加三个服务进程与 Nacos/Gateway，**会超预算**。
→ 这是**明确的待办**，见 [ADR-0021](../02-decisions/ADR-0021-docker-in-wsl2.md)「内存账」。

---

## 8. 磁盘与内存注意事项

- **一律装 D 盘**。C 盘空间紧张。
  **WSL2 与 Docker 的落盘位置天然满足这条**——WSL2 的整块"硬盘"就是
  `D:\aaaSoftware\wsl\Ubuntu-22.04\ext4.vhdx`，所以**WSL2 里装的一切**（Docker 引擎、镜像、
  容器、数据卷）**都物理落在 D 盘**。**不要**再给 Docker 单独指定 `data-root`——
  多一层间接，还容易和 WSL2 的磁盘管理打架。
- **每个中间件压 1G 堆**。ES 与 Kafka 默认都会按物理内存的一半算堆，四个中间件
  加起来会直接吃掉一半内存。
- **按需启动**。做内容域时不需要 Kafka 与 ES。
- ⚠️ **两套历史 Kafka 安装都还在磁盘上，别删**：`D:\aaaSoftware\kafka-3.9.1`（原生，已废弃）
  与 WSL2 里的 `/root/kafka`（手工装，已停用）。**它们是退路**——本次改动还没跑过完整里程碑，
  等 M4 做完再清理。理由见 [ADR-0021](../02-decisions/ADR-0021-docker-in-wsl2.md)。

---

## 9. 生产环境差异（★ 面试可讲）

本节回答一个问题：**"你这套配置直接上生产有问题吗？"** 答案是"有好几处，
而且我知道是哪几处"。

| 项 | 本地开发 | 生产环境必须改成 |
|---|---|---|
| **ES 安全** | `xpack.security.enabled: false`，无认证、无 TLS | **开启**：TLS 传输加密 + 内置用户/API Key 鉴权 + 细粒度角色。关闭安全等于任何人只要能访问 9200 端口就能读写删全部数据 |
| **ES 节点** | `discovery.type: single-node`，单节点 | 多节点集群 + 专用 master 节点，副本数 ≥ 1，否则单点故障就直接丢数据 |
| **Kafka 副本** | `replication-factor 1` | **≥ 3**（配合 `min.insync.replicas=2`）。副本为 1 时，broker 挂了该分区数据就没了 |
| **Kafka 监听** | `PLAINTEXT://` 明文 | `SASL_SSL`：认证 + 传输加密 |
| **Redis** | 无密码、6379 明文 | 设置 `requirepass`（或用 ACL）+ 只监听内网/绑定地址。裸奔的 Redis 是最常见的被入侵入口之一 |
| **MySQL** | 本地 root | 独立的低权限业务账号、最小授权、连接走 TLS |
| **凭据管理** | 明文写在配置里 | 环境变量 / 配置中心，**绝不入库**（spec §10.9） |
| **监听地址** | 全部 `127.0.0.1` | 按网络拓扑绑定，不直接暴露公网 |

> **一句话**：本地配置里所有的"关安全、图省事"都是**有意为之的开发便利**，
> 每一项都对应生产上的一个具体风险。能把这个对照表讲清楚，比"我搭了个 ES 集群"
> 更能说明你真的理解这些东西在干什么。
