# Docker 交叉测试

## 运行环境

宿主只需要 Docker 和 Docker Compose，并使用 Linux 容器。Windows 使用 PowerShell，macOS 和 Linux 使用 Bash；Java、Node、Python、Go、C、Chromium、Envoy 和注册中心服务分别在容器中运行。宿主不安装这些 SDK 或编译器。

镜像按 `linux/amd64`、`linux/arm64` 构建，默认选择 Docker 服务端的架构。`--platform` 可以选择另一架构，此时需要 Docker 提供相应的模拟运行能力。平台支持和实际验收结果分别记录在[迁移计划](../../document/testing/METEOR_TEST_DOCKER_PLAN.md)中。

每轮创建独立的工作目录卷和双栈网络。Java 测试容器的地址为 `runner.meteor.test`，其他对端使用各自的容器地址；应用之间通过容器网络通信。原有独立 Java 对端保留独立 JVM 和依赖类路径，原先在 JUnit 中创建的 SDK 仍在 runner 执行；其他语言使用各自的 SDK 容器。

rpcbind 的四个 SET/UNSET 场景要求本机控制目标，独立 C 容器与 runner 共享网络命名空间；其余 18 项 rpcbind 查询场景使用独立桥接网络。报告会记录实际网络模式，四项本机控制不计为独立网络互通。

| 资源 | 用途 | 保留时间 |
| --- | --- | --- |
| `meteor-test-<runId>-workspace` | 本轮源码、编译结果、控制文件和报告 | 归档后删除 |
| `meteor-test-<runId>-network` | 本轮应用、对端和注册中心通信 | 结束后删除 |
| `meteor-test-gradle-<cacheKey>` | Gradle 发行包和依赖缓存 | 跨轮次保留 |
| `meteor-test-maven-<cacheKey>` | Maven 本地仓库中的开发依赖 | 跨轮次保留 |

工作目录由构建完成的 runner 镜像初始化，不使用宿主源码目录绑定，因此容器中的源码路径始终为 `/workspace/meteor`。Docker 镜像和缓存卷可以复用；容器共享所属 Docker 虚拟机的时钟。

## 用法

在 Meteor 仓库根目录运行完整交叉测试：

```bash
./meteor-test/docker/run.sh
```

```powershell
.\meteor-test\docker\run.ps1
```

默认执行 `:meteor-test:test --no-daemon --console=plain --rerun-tasks`。首次运行构建各语言镜像并下载依赖；后续构建复用 Docker 缓存。已经构建当前源码对应的镜像时，可以加 `--no-build`。

`METEOR_TEST_CACHE_KEY` 选择 Gradle、Maven 缓存卷，默认值为当前架构 `amd64` 或 `arm64`。同一个缓存键同时只运行一个 Gradle runner；并行 CI 任务使用不同的键，并将架构写入键名。键名以字母或数字开头，其余字符可使用字母、数字、点、下划线和连字符。缓存键及实际卷名会记录到该轮的 `compose.env`。

```bash
METEOR_TEST_CACHE_KEY=amd64-job-a ./meteor-test/docker/run.sh --no-build
```

```powershell
$env:METEOR_TEST_CACHE_KEY = 'amd64-job-a'
.\meteor-test\docker\run.ps1 --no-build
```

单个 runner 内的对端容器及并发场景仍按测试本身运行。

筛选具体场景时，`--` 后的参数逐项交给 Gradle：

```bash
./meteor-test/docker/run.sh --no-build -- \
  :meteor-test:test --no-daemon --console=plain --rerun-tasks \
  --tests 'net.hasor.realmeteor.protocol.grpc.*'
```

```powershell
.\meteor-test\docker\run.ps1 --no-build -- `
  :meteor-test:test --no-daemon --console=plain --rerun-tasks `
  --tests 'net.hasor.realmeteor.protocol.grpc.*'
```

矩阵筛选继续使用现有的 `-Dmeteor.matrix.protocols`、`discoveries`、`scalarProfiles`、`complexProfiles`、`writeProtocols` 和 `readProtocols` 参数。运行其他 Gradle 任务时，也直接在 `--` 后指定任务及参数。

仓库使用本地 Hasor SNAPSHOT 依赖时，启动脚本默认复制宿主 `~/.m2/repository/net/hasor` 中的仓库数据，也可以显式指定：

```bash
./meteor-test/docker/run.sh --maven-seed /path/to/repository/net/hasor
```

```powershell
.\meteor-test\docker\run.ps1 --maven-seed 'C:\path\to\repository\net\hasor'
```

此目录包含 Maven 坐标对应的 POM、JAR 和仓库元数据。启动脚本不复制 `settings.xml`、用户凭证或其他 Maven 配置。未提供本地开发依赖时，runner 会给出对应坐标提示；Gradle 仍可复用已有缓存，并按仓库配置解析依赖。

| 参数 | 含义 |
| --- | --- |
| `--no-build` | 使用已有镜像；测试源码取自该 runner 镜像 |
| `--keep-workspace` | 归档后保留本轮工作目录卷 |
| `--prepare-only` | 只准备镜像、卷、网络和停止状态的 runner，不执行测试 |
| `--platform linux/amd64` | 指定目标平台，也可选择 `linux/arm64` |
| `--maven-seed PATH` | 指定 `net/hasor` 仓库数据目录 |
| `--` | 后续参数全部作为 Gradle 参数传递 |

镜像可通过 `METEOR_TEST_IMAGE_<RUNTIME>` 环境变量指定。`RUNTIME` 可取 `RUNNER`、`JAVA`、`PYTHON`、`NODE`、`NATIVE`、`GO`、`BROWSER`、`ENVOY`、`NACOS`、`ZOOKEEPER` 和 `H2`；例如 Java 对端使用 `METEOR_TEST_IMAGE_JAVA`。指定镜像的操作系统和架构必须与本轮平台一致。

注册中心环境通过 Nacos readiness API、ZooKeeper 会话握手及 H2 JDBC 连接确认就绪；启动失败保留日志并释放所属容器。

## 结果和资源

每轮结果写入仓库的 `build/docker-runs/<runId>`，`runId` 由 UTC 时间和随机后缀组成。目录包含：

1. 启动参数、Compose 配置、Docker 版本、镜像 ID、摘要和架构。
2. 镜像构建日志、runner 日志、容器清单及退出码。
3. 各项目的 JUnit XML、HTML 报告和逐案例互通报告。
4. 对端日志、容器身份与网络记录，以及独立应用和 Go 对端的控制文件。

成功和失败都会先导出报告，再清理本轮容器、网络和工作目录卷。Gradle、Maven 缓存及构建镜像继续保留。归档失败时返回非零退出码并保留工作目录；`--keep-workspace` 同样保留工作目录供排查。

`--prepare-only` 创建停止状态的默认 runner。需要继续执行时，使用该轮保存的环境配置，并在 `compose run` 中显式传入 Gradle 参数：

```bash
docker compose --env-file build/docker-runs/<runId>/compose.env \
  -f meteor-test/docker/compose.yaml run --no-deps --use-aliases runner \
  :meteor-test:compileTestJava --no-daemon --console=plain
```

准备操作本身没有测试结果。手动执行产生的报告保留在工作目录卷中；结束后先导出所需报告，再清理本轮资源。运行中的对端通过 `net.hasor.meteor.test.run=<runId>` 标签归属当前轮次，停止这些对端后，可用保存的 Compose 配置执行 `down --remove-orphans --volumes`，删除本轮 runner、网络和工作目录卷。外部声明的 Gradle、Maven 缓存卷继续保留。

## 实际验收

2026-10-24，Bash 入口在 WSL2/Linux amd64 完整执行 **4,063 项、238 个测试类，全部通过，0 跳过**，原有 4,059 项全部保留。Windows PowerShell 5.1 同轮完成 26 项，包含直连及三种注册后端的独立应用容器通信。运行结束后，本轮容器、网络和工作卷均已回收，Meteor 内核保持不变。

macOS 尚无实机验证；arm64 仅完成 Java 镜像构建，完整镜像及测试尚未验证。具体范围、历史失败和证据见[迁移计划](../../document/testing/METEOR_TEST_DOCKER_PLAN.md)及[测试审计](../../document/testing/METEOR_TEST_AUDIT.md)。
