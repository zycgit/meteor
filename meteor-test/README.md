# Meteor RPC 交叉测试

`meteor-test` 验证 Meteor 与各语言 SDK、标准库及独立协议实现的互通，覆盖直连、注册发现、安全传输和类型映射，与 `met-example` 独立。

测试在 Docker 内构建和启动，通过公开 API 执行真实 RPC。默认 16 个工作组并发，共用语言容器，Java SDK 保持独立 JVM 与依赖隔离。

[SDK 版本兼容](../README.md#sdk-版本兼容测试)复用相同的服务和调用入口，按 SDK 发行版本分别验证两个调用方向，并在报告中记录实际解析版本。

## 运行

宿主需要 **JDK 17、Docker 和 Compose**，语言工具链在容器内准备。以下命令在 Meteor 仓库根目录执行：

```bash
./meteor-test/runnxn.sh                       # 全部测试，默认 16 组
./meteor-test/runnxn.sh --groups 4            # 降低并发，1 表示串行
./meteor-test/runnxn.sh --tests 'net.hasor.realmeteor.protocol.grpc.*'
```

Windows 使用 `.\gradlew.bat :meteor-test:dockerTest`，测试参数相同。`--tests` 支持类名、`类名.方法名` 和 `*`，可重复传入。

Linux / WSL 遇到时钟回拨时，使用 `sudo ./meteor-test/runnxn.sh` 暂停 timesyncd 和 WSL chronyd；结束或中断后恢复原状态，终端显示暂停、失败和恢复结果。

## 参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--groups N` | `16` | 并发工作组数，范围 `1` 到 `16` |
| `--tests PATTERN` | 全部 | 按类或方法筛选，可重复 |
| `--skipError true\|false` | `true` | 遇错继续其余可执行测试；最终仍报告失败。`false` 停止后续测试并等待收尾 |
| `--no-build` | 关闭 | 禁止构建或拉取镜像，仍编译当前源码 |
| `--rebuild` | 关闭 | 重建工具链镜像，与 `--no-build` 互斥 |
| `--platform PLATFORM` | Docker 架构 | `linux/amd64` 或 `linux/arm64` |
| `--maven-seed PATH` | `~/.m2/repository/net/hasor` | 本地 Maven 产物种子；显式路径须存在，默认路径缺失时使用空种子 |
| `--prepare-only` | 关闭 | 只准备环境和构建，收尾后保留工作卷 |
| `--keep-workspace` | 关闭 | 测试收尾后保留工作卷 |
| `-h`、`--help` | — | 查看脚本帮助 |

环境变量：`METEOR_TEST_GROUPS`、`METEOR_TEST_PLATFORM` 设置默认并发和平台，命令行可覆盖；缓存及镜像配置见 [Docker 说明](docker/README.md)。

## 结果与清理

日志实时输出，每 15 秒汇总完成组数、用例数、失败和跳过数。结果写入 [report.md](report.md)，其中的产物路径相对于 `meteor-test/`。完整日志与 XML 位于仓库 `meteor-test/build/docker-runs/`，每次运行先清除旧结果。

控制文件位于 `meteor-test/build/meteor-test-control/`，校时状态和日志位于 `meteor-test/build/meteor-test-clock/`，本轮校时记录同时归档到 `meteor-test/build/docker-runs/clock-state/`。宿主 Gradle 缓存和日志使用仓库共享的 `.gradle/user-home/`；单测及子进程的临时文件、用户缓存位于所属模块 `build/`。

```bash
./meteor-test/build_report.sh                # 重建当前报告，不重新测试
./gradlew :meteor-test:dockerCleanup          # 清理保留的环境和工作卷
```

Ctrl+C 后等待日志归档和资源清理；再次运行会先停止旧轮。默认回收容器、网络和临时卷，保留 Gradle 依赖缓存。详细维护方法见 [Docker 说明](docker/README.md) 和 [报告工具](scripts/README.md)。
