# Docker 测试环境

## 介绍

[compose.yml](compose.yml) 统一管理一套名为 `meteor-test` 的测试环境，包含语言运行容器、注册中心与专用节点。宿主只需 JDK 17、Docker 与 Compose；Java 框架、应用、SDK 和其他语言程序在容器内构建。默认 16 表示并发场景数，容器数量不随 testcase 或分组数量增长。

正式入口、参数、报告与清理见 [README.md](../README.md)，最近一次验收见 [report.md](../report.md)。

## 维护命令

常用启动方式见 [运行说明](../README.md#运行)。需要排查环境时，可在仓库根目录使用：

```bash
# 只准备环境和容器内构建，保留工作卷
./meteor-test/runnxn.sh --prepare-only

# 测试结束后保留工作卷
./meteor-test/runnxn.sh --keep-workspace

# 清理保留的测试环境
./gradlew :meteor-test:dockerCleanup
```

其他配置：`--platform linux/amd64` 或 `linux/arm64` 指定容器架构；`--maven-seed PATH` 指定本地 `net/hasor` Maven 产物目录，默认读取 `~/.m2/repository/net/hasor`。Windows 使用 `gradlew.bat` 调用相同 Gradle 任务和参数。

环境名称固定：

- 容器：`meteor-test-runner`、`meteor-test-java-meteor`、`meteor-test-nacos` 等。
- 网络：`meteor-test-network`，所有服务共用。
- 临时卷：`meteor-test-peer`、`meteor-test-maven`、`meteor-test-workspace`。
- 依赖缓存：`meteor-test-gradle-<架构>`，默认架构为 `amd64` 或 `arm64`。

一次只运行一轮测试。容器、网络和临时卷默认在结束后自动清理，下次同名建立。清理命令核对资源归属，保留共享 Gradle 缓存。

重新启动时先请求旧测试停止，等待其清理完成，清除旧结果，再启动新轮。残留工作卷具有本仓库归档记录和唯一运行标签时，自动归档并清理；归属不明确则保留并报错。旧测试停止超时会直接失败。日志和报告统一写入仓库 `meteor-test/build/docker-runs/`，需要保留时在下次运行前自行复制。

默认 16 组并行，`--skipError true` 在用例或工作组异常后继续其余可执行测试，最终仍返回失败；`--skipError false` 停止后续测试。INT、TERM 始终停止并等待清理。已执行结果保留，未执行项单独列出，不计作通过或跳过。

## 容器与构建

| 服务 | 用途 |
| --- | --- |
| `runner` | Java 编排、调度与报告汇总 |
| `java-meteor` | Java 构建、JUnit 场景及 Meteor 调用 |
| `java-meteor-peer` | 需要独立 hostname 和网络节点的 Meteor 对端 |
| `java-sdk` | 独立 JVM 和 classpath 的 Java SDK 对端 |
| `python`、`node`、`go`、`native` | 各语言构建与执行；native 包含 C/C++ |
| `browser` | Chromium 与浏览器测试 |
| `nacos`、`zookeeper`、`jdbc` | Meteor 注册存储服务 |
| `zookeeper-native` | SDK 注册模型专用 ZooKeeper |
| `envoy` | gRPC-Web 代理场景 |
| `native-local` | rpcbind 本机控制场景的专用节点 |

语言镜像位于各 `peer/<语言>/Dockerfile`；C/C++ 共用 native 工具链，Node 与浏览器使用不同构建目标。编排、存储及代理镜像位于本目录。

镜像默认值、网络和卷名由 Compose 定义。`METEOR_TEST_IMAGE_*` 可覆盖对应镜像，归档记录实际使用的镜像身份。`METEOR_TEST_CACHE_KEY` 选择依赖缓存，默认按架构命名。

默认复用已有工具链镜像，缺失时由 Compose 构建；外部注册中心镜像仅在缺失时拉取。修改 Dockerfile 或镜像内工具链依赖后，使用 `--rebuild` 重建。`--no-build` 禁止构建和拉取镜像，缺失时直接报错。Dockerfile 使用 Docker 内置构建前端。

源码作为只读输入导入空白工作卷，编译输出不写回宿主源码。Gradle 依赖缓存跨轮保留，Maven、peer 和 workspace 使用上述固定临时卷名。所有模式均在容器内构建当前源码，不读取旧测试结果。

宿主 Wrapper 默认将 Gradle 缓存及 daemon 日志放入仓库共享的 `.gradle/user-home/`，可显式设置 `GRADLE_USER_HOME`。容器通过同名环境变量使用受管理的 Gradle 缓存卷；运行日志和结果归档到宿主 `meteor-test/build/docker-runs/`。用户 Maven 目录仅作为只读依赖种子。

## 网络与进程

业务调用通过 Compose DNS 和真实监听端口通信。支持动态端口的提供者绑定端口 `0`，就绪后报告实际端口。TLS 按对应 DNS 校验证书。

场景共用语言容器，分别持有自己的进程、端口、临时目录、HOME、XDG 缓存和注册数据。Java SDK 使用独立 JVM 与运行图，不加载 Meteor 场景输出。Linux 原生执行器负责进程监督、取消及后代回收；JUnit 不能删除共享容器。

固定端口、节点故障或本机控制场景使用专用节点及必要的排他调度。程序退出、exec 中断和容器退出分别记录，清理失败使整轮失败。

`runnxn.sh` 收到第一次 Ctrl+C 或 TERM 后写入取消请求，保持 Gradle 客户端和控制台日志，等待本轮程序停止、归档和环境清理，最后返回 `130` 或 `143`。使用 sudo 时，退出前还会恢复校时原状态。未确认退出时保留工作卷与诊断。

直接调用 Gradle 或 `gradlew.bat` 同样支持运行归属、取消和残留恢复；保持中断后终端日志的行为由 `runnxn.sh` 提供。

## WSL 时钟

容器共享宿主墙钟。Nacos 遇到回拨时，固定增加等待不能恢复已忽略的时间戳更新；时间环境和业务失败分别记录。

需要暂停 Linux / WSL 校时时，在仓库根目录执行：

```bash
sudo ./meteor-test/runnxn.sh
```

入口暂停 Linux 的 `systemd-timesyncd` 和 WSL 虚拟机的 `chronyd`。结束或收到 INT、TERM 后，先等待测试清理，再恢复原状态，原先未运行的保持不变。暂停和恢复结果直接打印。

Linux sudo 不提供 Windows 管理员权限；需要时会请求 UAC。权限或校时准备异常按 `--skipError` 处理：默认继续可执行测试，设为 `false` 则停止，异常均计入最终失败。Gradle 以原 sudo 调用者运行，沿用其 HOME 和缓存。普通入口不修改校时服务。此控制不关闭系统时钟，不修改系统时间、tick 或 freq；其他校时来源需独立确认。

Gradle 自动写入 `meteor-test/build/docker-runs/report.md` 和 `meteor-test/report.md`，下次运行时清除旧结果。`meteor-test/report.md` 中的产物路径相对于 `meteor-test/`，使用 `build/docker-runs/`。校时恢复后追加脚本阶段异常，与 RPC 失败用例分列；报告任务即使失败，也先写出汇总、失败用例及原因。

校时诊断保存在 `meteor-test/build/meteor-test-clock/`，并复制到本轮归档的 `clock-state/`；恢复错误会列出缺失确认或具体退出原因。
