# Meteor RPC 交叉测试

`meteor-test` 验证 Meteor 与各语言 SDK、标准库及独立实现的互通，覆盖直连、注册发现、安全传输、类型映射和 SDK 版本兼容。

本页介绍运行、参数和结果。源码结构、测试原理、开发规范及维护命令见[开发说明](DEVELOPMENT.md)。

## 运行

宿主需要 **JDK 17、Docker 和 Compose**，其余工具链由容器提供。以下命令均在 Meteor 仓库根目录执行；根 `build.sh` 只构建产品模块，交叉测试单独运行。

```bash
./meteor-test/runnxn.sh                       # 查看帮助，不运行测试
./meteor-test/runnxn.sh all                   # 全部测试，默认 16 组并发
./meteor-test/runnxn.sh all --jobs 4          # 降低并发，1 表示串行
./meteor-test/runnxn.sh rpc grpc              # 按协议运行
./meteor-test/runnxn.sh rpc soap 1.2          # 指定线协议版本
./meteor-test/runnxn.sh rpc dubbo 2           # Dubbo 2 大版本
./meteor-test/runnxn.sh test '*GrpcInteropTest'
./meteor-test/runnxn.sh test '*Standard*MatrixTest' # 标准 API 自动矩阵
./meteor-test/runnxn.sh all --case '*framework*'   # 框架互调及注册中心集成
./meteor-test/runnxn.sh rpc soap 1.2 --tests '*Soap12InteropTest.*'
```

`test`、`--tests` 支持类名、`类名.方法名`、`类名.方法名[参数显示名]` 和 `*`；`--tests` 可重复。协议、版本和用例筛选取交集，无匹配用例时报告失败。

标准矩阵根据各工程的能力声明自动匹配提供者和消费者，覆盖订单、基础类型和复杂对象 API。运行前打印发现数量、本轮选中数量，以及 RPC、消费者语言 → 提供者语言统计；特殊用例的参数在运行期展开，单独列出发现范围。

版本指 RPC 线协议版本：Dubbo 使用 `2`、`3`，SOAP 使用 `1.1`、`1.2`，Hessian 使用 `1`、`2`；gRPC 等省略版本。SDK 发行版本由测试用例选择。

Windows 使用相同的 Gradle 任务：

```powershell
.\gradlew.bat :meteor-test:dockerTest
.\gradlew.bat :meteor-test:dockerTest --rpc soap --rpc-version 1.2
.\gradlew.bat :meteor-test:dockerTest --tests '*GrpcInteropTest'
```

## 参数

- `--jobs N`、`--groups N`：并发数，默认 `16`，范围 `1` 到 `16`。
- `--tests PATTERN`：按类或方法筛选，可重复。
- `--case PATTERN`：按标准矩阵的 caseId 或向量标识筛选，可重复，支持 `*`。失败输出中的完整 caseId 可直接用于单例重跑。
- `--skipError true|false`：默认 `true`，遇错继续其余可执行测试，最终仍报告失败；`false` 停止后续测试并等待收尾。
- `--no-build`：禁止构建或拉取镜像，仍编译当前源码；缺少镜像时报错。
- `--rebuild`：重建工具链镜像，与 `--no-build` 互斥。
- `--platform PLATFORM`：指定 `linux/amd64` 或 `linux/arm64`，默认使用 Docker 架构。
- `--maven-seed PATH`：只读导入本地 Maven 的 `net/hasor` 制品，接受仓库根目录或 `net/hasor` 目录。
- `--prepare-only`：只准备环境和构建，收尾后保留工作卷。
- `--keep-workspace`：测试收尾后保留工作卷。
- `-h`、`--help`：查看脚本帮助。

Maven 种子默认取 `~/.m2/repository/net/hasor`，也可用 `-Dmaven.repo.local` 指定仓库；`--maven-seed` 优先。显式目录必须存在，默认目录缺失时使用空种子，容器按正常依赖仓库解析。

```bash
./meteor-test/runnxn.sh all --maven-seed /path/to/repository
./gradlew -Dmaven.repo.local=/path/to/repository :meteor-test:dockerTest
```

环境变量 `METEOR_TEST_GROUPS`、`METEOR_TEST_PLATFORM` 设置默认并发和平台，命令行可覆盖；`METEOR_TEST_IMAGE_*` 覆盖 [Compose](docker/compose.yml) 中对应镜像，`METEOR_TEST_CACHE_KEY` 选择 Gradle 缓存卷，默认按架构区分。宿主 Gradle 缓存可通过 `GRADLE_USER_HOME` 指定。

## 结果与清理

结果写入 [report.md](report.md)，完整日志和 XML 位于 `meteor-test/build/docker-runs/`，报告内路径相对于 `meteor-test/`。日志实时输出，每 15 秒汇总进度。标准矩阵失败会列出 API、RPC、双方端点与语言、发现方式、执行阶段和日志；未执行、准备失败及结果未知分别记录。

本轮完整计划位于归档中的 `meteor-test/build/groups/manifest.json`，各工作组的 `matrix.json` 保存实际分配的用例。能力不匹配在计划阶段说明原因，已匹配用例的运行失败不会变成“不适用”。

一次只运行一轮测试。新一轮先停止旧测试、等待清理并清除旧结果，需要保留的报告和日志应提前复制。

```bash
./meteor-test/build_report.sh                # 重建当前报告，不重新测试
./gradlew :meteor-test:dockerCleanup          # 清理保留的环境和工作卷
```

Ctrl+C 后等待进程停止、日志归档和资源清理。默认回收容器、网络及临时卷，保留 Gradle 依赖缓存；未确认退出或资源归属不明时保留现场并报告失败。

## WSL 时钟

需要暂停 Linux / WSL 校时时执行：

```bash
sudo ./meteor-test/runnxn.sh all
```

入口暂停 `systemd-timesyncd` 和 WSL 的 `chronyd`，结束或中断后恢复原状态；测试仍以原 sudo 调用者运行。普通启动不调整校时服务，Windows 侧操作可能请求 UAC。校时准备异常按 `--skipError` 决定是否继续，准备及恢复异常均计入最终失败。

校时日志位于 `meteor-test/build/meteor-test-clock/`，并归档到本轮 `build/docker-runs/clock-state/`。
