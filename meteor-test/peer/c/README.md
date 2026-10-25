# 独立 ONC RPC 对端

`../../src/test/resources/oncrpc/interop.x` 是互通测试的 XDR/RPC 契约。`generated` 中的头文件、编解码、客户端和分派器由系统 `rpcgen` 生成，保留工具原始输出。手写 C 程序只提供业务实现、进程控制及 libtirpc 调用，不依赖 Meteor、JVM 或测试类路径。

## 正式测试

宿主准备 JDK 17、Docker 与 Compose，在仓库根目录通过 Gradle 9.5 Wrapper 执行 ONC RPC 场景：

```bash
./gradlew :meteor-test:dockerTest \
  --tests 'net.hasor.realmeteor.protocol.oncrpc.*'
```

Windows 使用 `.\gradlew.bat :meteor-test:dockerTest` 并传入相同参数。C 编译器、rpcgen 和 libtirpc 由 native 容器提供，JUnit 在常驻容器内启动独立对端进程。完整交叉测试使用 `./gradlew :meteor-test:dockerTest`；运行配置与结果目录见 [Docker 用法](../../docker/README.md)。

## 契约维护与本地诊断

手动诊断需要 Bash、GCC、`rpcgen`、libtirpc、`jq` 和 GNU coreutils；正式测试由 native 镜像提供这些工具。Ubuntu 对应包为 `gcc`、`rpcsvc-proto`、`libtirpc-dev`、`jq`、`coreutils`。头文件默认读取 `/usr/include/tirpc`，可通过 `TIRPC_INCLUDE` 指定独立解包目录，直接执行脚本时也可使用优先级更高的 `--include`。

在仓库根目录执行：

```bash
bash meteor-test/peer/c/build-native.sh \
  --output meteor-test/build/oncrpc-native
```

诊断构建可以显式指定头文件目录：

```bash
bash meteor-test/peer/c/build-native.sh \
  --output meteor-test/build/oncrpc-native --include /absolute/path/to/tirpc
```

输出 `oncrpc_peer`、`oncrpc_rpcbind_peer` 和 `native-peer-runtime.json`。清单记录实际编译命令、日志、源码、ELF 文件、加载的 libtirpc 及工具版本。这些手动命令用于维护契约和诊断本地程序，正式测试使用镜像内的编译产物。

重新生成或检查官方生成文件：

```bash
bash meteor-test/peer/c/regenerate.sh
bash meteor-test/peer/c/regenerate.sh --check
```

## 业务对端的手动诊断

启动 TCP 服务：

```bash
meteor-test/build/oncrpc-native/oncrpc_peer --command=server --transport=tcp --port=0
```

服务输出 `READY <port>`，标准输入关闭后正常退出。上述诊断命令默认绑定回环地址；容器场景使用 `0.0.0.0` 并通过 Docker DNS 访问。注册分派器时传入 `protocol=0`，不访问系统的 111 端口。UDP 使用 `--transport=udp`；`--host=::1` 选择 IPv6 回环地址，两端使用相同地址。

另一个终端调用输出的端口：

```bash
meteor-test/build/oncrpc-native/oncrpc_peer --command=client --transport=tcp \
  --port=指定端口 --scenario=echo --value=hello --auth=none
```

成功输出 `RESULT echo=hello`。`--auth=sys` 使用固定测试身份 `uid=1001`、`gid=1002`、附加组 `10,20`。程序号为 `0x31234567`，业务版本为 `1`。

| 过程号 | 场景 | 参数与结果 |
| --- | --- | --- |
| 0 | `null` | 无参数、无结果的标准 NULL 调用 |
| 1 | `echo` | 字符串，原样返回 |
| 2 | `add` | 两个 int，计算 `left * 100 + right`，验证参数顺序 |
| 3 | `mirror` | `onc_sample`，逐字段原样返回 |
| 4 | `ping` | void 调用，仍返回标准成功响应 |
| 5 | `crash` | 返回 RPC `SYSTEM_ERR` |
| 6 | `auth` | 返回实际收到的认证 flavor 与 AUTH_SYS 身份 |
| 7 | `delay` | 等待指定毫秒数后返回相同数值 |
| 8 | `increment` | 按参数增加进程内计数并返回结果 |
| 9 | `count` | 查询进程内计数 |
| 10 | `reset` | 清零进程内计数 |
| 11 | `bytes` | 可变 opaque，验证完整字节与长度 |

`onc_sample` 包含 signed/unsigned int、hyper、float/double、bool、字符串、固定与可变 opaque、固定与可变数组、枚举、判别联合和递归可选节点。

错误场景包括 `wrong-program`、`wrong-version`、`wrong-procedure`、`bad-arguments`、`unsupported-auth`、`weak-auth` 和 `crash`，输出 `EXPECTED_ERROR <scenario>=RPC_*`。`weak-auth` 用于调用要求 AUTH_SYS 的 Meteor 测试端点。

## rpcbind 对端

```bash
meteor-test/build/oncrpc-native/oncrpc_rpcbind_peer --command=server --transport=tcp --port=0
meteor-test/build/oncrpc-native/oncrpc_rpcbind_peer --command=client --transport=tcp \
  --port=指定端口 --version=4 --scenario=lookup
```

该测试服务在自己的动态端口发布程序 `100000` 的版本 `2/3/4`，不启动或修改系统 rpcbind。预置测试程序 `536870913`、业务版本 `1`，TCP 端口 `8123`、UDP 端口 `8124`。

## 安全传输边界

libtirpc 对端互通使用明文 TCP/UDP。`OncRpcSecureScenarioTest` 通过 Meteor 提供者与 Meteor 消费者验证 TCP TLS 1.2/1.3、UDP DTLS 1.2、证书及双向认证；这些是 Meteor 自体安全传输场景。RFC 9289 的 AUTH_TLS/STARTTLS 自动升级未纳入当前实现。
