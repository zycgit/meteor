# 测试维护工具

本目录保存契约生成和校时控制等维护工具，不承担测试调度。环境准备由 Gradle 完成，计划、调度和报告归根 `src/main/java/net/hasor/meteor/test/nxn`，Linux 进程监督归 `src/main/c`。完整运行见 [README.md](../README.md)，所有语言业务代码归 `peer/<语言>`。

Docker 使用固定的 `meteor-test` 环境，同一时间只执行一轮；容器名为 `meteor-test-<服务>`，网络为 `meteor-test-network`。报告和日志固定写入仓库 `meteor-test/build/docker-runs/`，每轮开始时清除旧结果。重新启动会先请求旧轮停止并等待清理。残留工作卷具有本仓库归档记录和唯一运行标签时，自动归档清理；归属不明确或停止超时则报错，不覆盖旧环境。手动清理见 [Docker 维护](../docker/README.md#维护命令)。

`runnxn.sh` 收到第一次 Ctrl+C 或 TERM 后写入取消请求，保持 Gradle 客户端和控制台日志，等待用例停止、归档、资源清理及 sudo 校时恢复，最后分别返回 `130` 或 `143`。直接调用 Gradle 或 `gradlew.bat` 同样支持运行归属、取消和残留恢复；保持中断后终端日志的行为由 `runnxn.sh` 提供。

## 类型报告

从当前实际运行的 `interoperability` 目录生成类型矩阵。输入路径必须明确指定，报告不读取协议清单中的历史通过状态。

```bash
./gradlew :meteor-test:nxnTypeReport \
  --run-directory meteor-test/build/docker-runs/meteor-test/build/interoperability \
  --expectations <reviewed-expectations.json> \
  --output document/testing/METEOR_TYPE_SUPPORT.md
```

Markdown 是面向读者的汇总：按语言和调用方向分节，协议与编码为行、数据类型为列；Java 8 时间单独展示，映射与限制按语言、协议和原因归纳。只显示当前语言的声明类型，无用例的方向和协议用“未验证”说明。表格不逐案列举，也不在每格重复通过数。提供者和消费者的计划、实际向量及全部单元格结论一致时合并为双向表；存在未执行项或差异时分别展示。

同时保存 `METEOR_TYPE_SUPPORT.json` 作为审计数据，包含原始 caseId、内部运行标识、类型、方向、编码、JUnit XML 位置和逐案状态。`--details-prefix` 可单独指定 JSON 的路径前缀。C 类型使用已签入的 `src/test/resources/matrix/c-type-catalog.json`；`--c-catalog` 可指定审阅过的替代目录。

运行目录必须同时包含 `selected-cases.json`、`summary.json`、`configuration.json` 和 `test-results/TEST-*.xml`。Java 报告框架核对内部运行标识、测试启动状态、缓存复用、计数、全部用例 ID 和 XML 结果；重复、缺失或冲突会报错。定向运行的筛选条件原样显示，不与另一轮拼接。

## 期望数据

正式期望位于 [type-support-expectations.json](../src/test/resources/matrix/type-support-expectations.json)，包含：

- `schemaVersion`：固定为 `1`。
- `review`：`reviewedBy` 和 `basis` 必填，说明谁审查及依据。
- `rules`：按 `select` 选择语言、协议、profile、角色、类型、向量或精确 caseId。字符串支持普通 glob；数组表示其中任一匹配。`caseId` 仅将 `*`、`?` 视为通配符，JUnit 参数方括号按字面匹配。最高 `priority` 必须只有一条规则胜出。
- `support`：`supported`、`mapped`、`limited`、`unsupported`、`unverified`。
- `outcome`：`roundtrip`、`normalization`、`rejection`，默认 `roundtrip`。拒绝和规范化不能宣称无损支持；不支持必须对应精确拒绝。
- `reason`：必填的审查说明。可附 `sdk`、`nativeType`、`wireType`、`encoding`、`transport`。
- `plannedCases`：发布前确定的完整目标清单，每项提供精确 `caseId`、`language`、`protocol`、`profile`、`role`、`type`、`vector`，时间项另设 `section: java-time`。本轮未执行的条目仍保留为 `missing/未验证`。此清单只允许维度字段，禁止携带旧运行的 `runId`、`testStatus`、`support` 或 `junitXml`。
- `caseMappings`：为旧 Date、Decimal、Complex 等没有新显示名的测试补充上述维度；仅映射精确 caseId，不能凭资源文件或同语言其它协议推导支持。
- `axes`：可配置报告中的语言、协议清单及计划 profile。没有相应 SDK 或方向时以文字汇总为未验证；显式计划但未执行的 profile 保留在表中。

没有匹配的审查规则时，即使 JUnit 通过也保持未验证。JUnit 失败始终显示失败；拒绝测试通过只证明约束有效。一个类型有正常范围和拒绝范围时汇总为有限支持，有计划向量未执行时汇总为未验证。

`plannedCases` 为空时，报告明确限定于“本轮实际执行的类型向量”，不宣称覆盖完整范围。Java 自体、Meteor 提供者及消费者分别展示。浏览器不借用 Node 的服务端结果；gRPC-Web text 仍按 Protobuf 消息编码记录。

## 解析验证

类型报告的 Java 单测不启动 Docker 或 SDK：

```bash
./gradlew :meteor-test:test --tests 'net.hasor.meteor.test.nxn.NxnTypeReportTest'
```

## 契约生成

基础订单源契约位于 `src/test/resources/order`。Thrift 维护入口为 `scripts/thrift/regenerate.sh`，Java 生成绑定输出到 `peer/java/contracts/src/main/java`；其他语言绑定输出到各自 `peer/<语言>`。Meteor 场景、SDK 对端和公共契约使用各自的标准编译输出，生成步骤不复制 class。具体命令见 [Thrift 测试契约](../src/test/resources/thrift/README.md)。

## 校时控制

在仓库根目录执行 `sudo ./meteor-test/runnxn.sh`，入口通过 `with-clock-paused.sh` 暂停 Linux 的 `systemd-timesyncd`，通过 `wsl-clock-owner.ps1` 暂停 WSL 虚拟机的 `chronyd`。Windows 权限不足时请求 UAC。`--skipError` 默认 `true`，权限或暂停异常会明确记录，并继续可执行测试；设为 `false` 则停止后续测试。异常均使最终结果失败。

测试正常结束、失败或收到 INT、TERM 后，先等待测试清理，再恢复原状态；原先未运行的保持不变，暂停和恢复结果直接打印。Gradle 以原 sudo 调用者运行，保留其 HOME 和缓存。普通入口不修改校时服务。此控制不关闭系统时钟，不修改系统时间、tick 或 freq；其他校时来源需独立确认。见 [Docker 运行](../docker/README.md#wsl-时钟)。

守卫的状态、PowerShell 副本和完整日志保存在 `meteor-test/build/meteor-test-clock/guard.*/clock-state/`，成功和失败均保留。本轮归档的 `clock-state/` 包含远端命令结果、Windows owner 退出码及恢复确认；超时仍在恢复时，该归档是当时的快照，后续日志继续写入原状态目录。

Gradle 自动写入 `meteor-test/build/docker-runs/report.md` 和 `meteor-test/report.md`，下次运行时清除旧结果。`meteor-test/report.md` 中的产物路径相对于 `meteor-test/`，使用 `build/docker-runs/`。脚本在恢复校时后追加自身阶段的异常，与 RPC 失败用例分列。`./meteor-test/build_report.sh` 无参重建当前报告；`nxnReport` 在失败时先写汇总、用例及原因，再返回非零状态。未执行项单独列出，不计作通过或跳过。

完整验收见 [report.md](../report.md)。编排与报告框架的单测结果分别记录，不与 RPC 交叉用例合并；报告不复用旧轮结果。
