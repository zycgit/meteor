# 测试维护工具

## Thrift 契约生成

在 native 工具链容器中进入 `meteor-test/tools/thrift` 后执行：

| 工具 | 作用 | 用法 |
| --- | --- | --- |
| [prepare-compiler.sh](thrift/prepare-compiler.sh) | 构建 Thrift 0.25.0 Java 代码生成器 | `bash prepare-compiler.sh [输出目录]` |
| [regenerate.sh](thrift/regenerate.sh) | 生成互通测试的 Java 契约 | `bash regenerate.sh` |
| [regenerate-scalar.sh](thrift/regenerate-scalar.sh) | 生成基础类型测试的 Java 契约 | `bash regenerate-scalar.sh` |
| [regenerate-complex.sh](thrift/regenerate-complex.sh) | 生成复杂类型测试的 Java 契约 | `bash regenerate-complex.sh` |
| [regenerate-sdk.sh](thrift/regenerate-sdk.sh) | 使用 Thrift 0.22.0 生成 SDK 订单契约 | `bash regenerate-sdk.sh` |
| [regenerate-sdk-classic.sh](thrift/regenerate-sdk-classic.sh) | 使用 Thrift 0.10.0 生成 SDK 兼容契约 | `bash regenerate-sdk-classic.sh` |

前三个 `regenerate` 工具使用 0.25.0 编译器，可先准备并指定路径：

```bash
bash prepare-compiler.sh
export THRIFT_COMPILER="$PWD/../../build/thrift-compiler-0.25.0/bin/thrift"
bash regenerate.sh
```

## 负零诊断

[types-negative-zero-probe.mjs](diagnostics/node/types-negative-zero-probe.mjs)：检查 Protobuf JSON 编码对负零的转换。在 Node 测试容器运行期间，从仓库根目录执行：

```bash
docker exec meteor-test-node node /workspace/meteor/meteor-test/tools/diagnostics/node/types-negative-zero-probe.mjs
```

## 校时控制

| 工具 | 作用 | 用法 |
| --- | --- | --- |
| [with-clock-paused.sh](clock/with-clock-paused.sh) | 暂停 Linux 校时，测试结束或中断后恢复 | 在仓库根目录运行 `sudo ./meteor-test/runnxn.sh all`，自动调用 |
| [wsl-clock-owner.ps1](clock/wsl-clock-owner.ps1) | 暂停 WSL 的 chronyd 校时并恢复 | 由 `with-clock-paused.sh` 自动调用 |
