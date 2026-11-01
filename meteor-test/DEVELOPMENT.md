# meteor-test 开发说明

运行方法见 [README.md](README.md)。下文路径相对于 `meteor-test/`，命令从 Meteor 仓库根目录执行。

## 测试原理

[runnxn.sh](runnxn.sh) 调用 [nxn.gradle](nxn.gradle) 的 `dockerTest` 任务，由 [Compose](docker/compose.yml) 创建共享测试环境。当前源码导入工作卷后在容器内构建，编译输出不写回宿主源码。

- `runner` 负责调度和报告，`java-meteor` 执行 Java 构建与 JUnit；`java-sdk` 在独立 JVM 和 classpath 中运行 SDK。
- Python、Node.js、Go、native、browser 容器分别提供语言环境，native 包含 C/C++。Nacos、ZooKeeper、JDBC 提供注册存储；原生 ZooKeeper、Envoy 和专用节点供相应场景使用。
- JUnit 启动 Meteor 和对端程序，等待就绪后通过 Docker DNS、真实监听端口发起 RPC，再校验响应、错误和资源释放。默认 16 表示并发场景数，各场景共用容器。
- 矩阵方向以 Meteor 为基准：`self` 为 Meteor 两端，`provider` 为 SDK → Meteor，`consumer` 为 Meteor → SDK；按对端实际能力安排方向。

## 源码结构

- `src/main/java/net/hasor/meteor/test/nxn`：编排、调度及报告；`src/main/c`：Linux 进程执行器；`src/test`：框架测试。
- `peer/<语言>`：对端业务源码、启动脚本及工具链 Dockerfile；Java 的业务、JUnit、SDK 与公共契约分开组织，见下文。
- `docker`：Compose、编排与存储环境、代理配置。C/C++ 共用 native 工具链，Node.js 与浏览器使用同一 Dockerfile 的不同构建目标。
- `resource`：共享契约、数据、矩阵清单和证书。
- `tools`：契约生成、诊断与校时维护工具。
- `build`：本工程的编译、临时文件、用户缓存、控制状态及运行归档。

## 语言对端

### Java

- `peer/java/meteor` 的 `src/main/java` 保存 Meteor 应用与业务，`src/test/java` 保存场景；`peer/java/contracts` 保存公共接口、DTO 和生成绑定。SDK 源码按实际实现放在 `peer/java/<SDK>`。
- [Dockerfile](peer/java/Dockerfile) 提供 JDK 17；[build.gradle](peer/java/build.gradle) 聚合 SDK 编译和运行配置，`exportRuntimes` 导出实际依赖版本与独立类路径。
- 兼容版本共用源码及编译结果，不兼容版本使用显式版本子工程，例如旧命名空间的 Alibaba Dubbo、`javax` Metro、新版 Connect Kotlin。Connect Kotlin 只提供 Connect / gRPC-Web 消费者，Connect 服务端使用 Connect RPC Java。

### Python

- `peer/python/<协议>` 保存 `peer.py` 或提供者、消费者入口；`orders` 与 `jsonrpc/orders` 用于订单及发现，`types` 用于类型映射。[build-runtime.py](peer/python/build-runtime.py) 准备运行环境及 gRPC、Connect 绑定、SOAP 契约。
- [Dockerfile](peer/python/Dockerfile) 提供 Python 3.13；依赖按协议的 `requirements.txt` 安装。版本清单位于 `<协议>/versions`，由 [prepare-versions.py](peer/python/prepare-versions.py) 建立独立虚拟环境；旧 Zeep 使用 Python 3.12.16。
- SOAP 的消费者为 Zeep、提供者为 soapfish2，Hessian 仅有消费者；JSON-RPC、XML-RPC 使用标准库，其余协议使用对应 SDK。

### Node.js

- `peer/node/<协议>` 保存调用与服务入口、`generated` 绑定及锁文件；`order-registry-peer`、`types-peer` 分别用于发现与类型场景。[build-runtime.js](peer/node/build-runtime.js) 准备运行副本并打包浏览器资源。
- [Dockerfile](peer/node/Dockerfile) 使用 Node.js 24；[versions/profiles.json](peer/node/versions/profiles.json) 声明 SDK 版本及角色，`versions/manifests` 保存各自锁文件并据此隔离安装依赖。Connect 的不同消息 API、Thrift 的 BigInt 绑定由协议内 `versions` 管理。
- Dubbo、Hessian 仅有消费者；gRPC-Web 在浏览器运行。`triple-alpha` 使用 Dubbo alpha SDK 的 gRPC / Protobuf 兼容能力。Dubbo 的 npm 版本与 `dubboVersion` 线协议配置分别维护。

### Go

- `peer/go` 的 `main.go`、`*_peer.go` 保存协议入口，`order` 为订单两端，`types` 为类型场景，`gen` 为生成绑定；`dubbo`、`oncrpc` 是独立模块。
- [Dockerfile](peer/go/Dockerfile) 提供 Go 1.27.2，使用 `GOTOOLCHAIN=local`。`compatibility` 按版本保留独立 `go.mod`、`go.sum` 和二进制；[build-runtime.sh](peer/go/build-runtime.sh) 通过 `go version -m` 核对实际链接版本。
- 提供者报告就绪地址，消费者输出结果供 Java 校验；SOAP（gowsdl）和 XML-RPC（kolo/xmlrpc）当前仅有消费者。

### C

- `peer/c` 的 ONC RPC 入口使用 rpcgen / libtirpc，`generated` 保存互通绑定，`order` 保存订单两端；`grpc`、`thrift`、`soap`、`xmlrpc` 分别使用 gRPC C Core / protobuf-c、C GLib、gSOAP、xmlrpc-c。
- [Dockerfile](peer/c/Dockerfile) 提供 GCC、Make、契约生成器及原生库；[build-runtime.sh](peer/c/build-runtime.sh) 统一构建 C/C++。额外 SDK 版本由 `build-version.sh` 等脚本安装到独立目录，保留各自动态库路径。
- `jsonrpc` 只有[构建脚本](peer/c/jsonrpc/build.sh)：镜像构建时将固定提交的 `initlevel5/json-rpc-client` 下载到 `/opt/meteor-toolchain/jsonrpc-c`，校验后编译上游客户端，验证 C → Meteor。libtirpc 使用明文 TCP / UDP，ONC RPC 的 TLS / DTLS 使用 Meteor 两端。

### C++

- `peer/cpp/<协议>` 保存 SDK 入口及构建脚本，`order` 为 ONC RPC 订单两端，由 C 的订单 Makefile 一并构建。
- 与 C 共用 native 镜像中的 G++、CMake、生成器和原生库；基础版本使用系统包，额外版本保留独立安装目录、二进制和动态库路径，源码 SDK 固定发行版本或提交。
- gRPC、Thrift、SOAP、XML-RPC、JSON-RPC、ONC RPC 具有两端入口；Connect（Loams）、Hprose、Hessian 仅有消费者。

### 浏览器 JavaScript

- `peer/browser` 保存 Fetch、Hprose、Thrift、Dubbo alpha 等消费者；`peer/node/grpcweb` 保存 gRPC-Web 客户端、页面及打包脚本。
- Node.js 启动 Playwright Chromium，RPC 在页面中发出，Meteor 作为提供者。工具链来自 Node Dockerfile 的 `browser` 阶段，gRPC-Web 多版本沿用 Node 的版本配置与独立生成绑定。

### Shell

- [jsonrpc/consumer.sh](peer/shell/jsonrpc/consumer.sh) 使用 curl 保存、查询订单；其他文本及二进制协议的请求准备和断言位于 Java 的 `net.hasor.realmeteor.example.multilingual.Shell*ScenarioTest`。
- 仅验证 Shell → Meteor，Bash 和 curl 由 Node 容器提供；没有独立 RPC SDK 版本目录。

## 开发规范

详细要求以仓库 [AGENTS.md](../AGENTS.md) 和 [meteor-test-style](../.agents/skills/meteor-test-style/SKILL.md) 为准。

- 文档统一放在本工程根目录：`README.md` 介绍使用，`DEVELOPMENT.md` 记录开发，`report.md` 保存运行报告；子目录不另设说明文档。
- 场景归 `net.hasor.realmeteor`，业务模型归 `net.hasor.example`；单测与被测类同包。`meteor-test` 独立持有模型、契约和 SDK，不能通过源码、资源、生成脚本或 Docker 构建依赖 `met-example`。
- 一个测试方法验证一个场景；Meteor 端通过 `MetConfiguration` 等公开入口启动、发布和调用，互通必须走真实网络。按实际步骤用空行和中文注释分段，关键生命周期和断言留在场景中。
- 使用官方 SDK 公开 API 和生成契约，保留原厂制品。记录真实依赖组合及限制；兼容修复由 Meteor 承担，不改写对端报文、打补丁或增加隐藏重试。
- Compose 管理共享容器；场景只拥有自己的进程、端口、临时目录、缓存和注册数据。支持动态端口的提供者绑定 `0`，异步等待使用完成信号或有界条件；取消时回收本场景进程树。
- Java 使用普通类或 POJO；公共依赖版本遵循根 `gradle.properties`，独立 SDK 对端自行维护兼容版本及运行依赖。API / ABI 不兼容时分开编译与运行。
- 类或方法使用 `@NxnRpc(name = "soap", version = "1.2")` 声明协议；可重复声明，参数化场景用 `cases` 匹配原始显示名，方法声明覆盖类声明。构建自动汇总筛选清单。
- 修改后先运行受影响场景，再按影响范围扩大验证；分别记录通过、失败、跳过及未执行，不能拼接不同批次宣布全绿。测试数量和候选矩阵不等于已验证能力。

## 测试资源

- `resource/protocol/proto`：gRPC、gRPC-Web、Connect、Triple 的 Protobuf 契约。
- `resource/protocol/thrift`：订单、类型及互通契约；`resource/protocol/x`：ONC RPC 的 XDR/RPC 契约。
- `resource/protocol/wsdl`、`resource/protocol/xsd`：SOAP 服务契约和 XML 类型定义。
- `resource/data`：`basic`、`registry` 保存订单数据，`scalar`、`complex`、`types` 保存类型向量。
- `resource/matrix`：协议、对端及候选组合清单。
- `resource/certs/acceptance`：正常身份、不受信任身份及主机名不匹配场景的 TLS 证书；`resource/certs/multiprotocol` 使用独立 CA，当前没有专用生成脚本。证书、私钥仅供测试，PKCS12 密码为 `meteor-test`。

## 资源生成

契约、JSON 数据和矩阵清单直接维护；修改契约后更新所属 peer 的绑定。启动时自动生成的内容由各语言构建脚本准备，以下是已签入资源的维护命令。

```bash
# Java Thrift：在 native 工具链环境执行；prepare-compiler 只构建 Java 生成器
bash meteor-test/tools/thrift/prepare-compiler.sh
export THRIFT_COMPILER="$PWD/meteor-test/build/thrift-compiler-0.25.0/bin/thrift"
bash meteor-test/tools/thrift/regenerate.sh
bash meteor-test/tools/thrift/regenerate-scalar.sh
bash meteor-test/tools/thrift/regenerate-complex.sh
bash meteor-test/tools/thrift/regenerate-sdk.sh          # 自行准备 0.22.0 编译器
bash meteor-test/tools/thrift/regenerate-sdk-classic.sh  # 自行准备 0.10.0 编译器

# Go：需要 Go、protoc；Thrift 需含 Go 生成器的 0.25.0 编译器
bash meteor-test/peer/go/regenerate.sh
bash meteor-test/peer/go/regenerate-thrift.sh --compiler /path/to/thrift
bash meteor-test/peer/go/regenerate-soap.sh

# C / ONC RPC：需要 rpcgen；--check 只核对绑定与契约是否一致
bash meteor-test/peer/c/regenerate.sh
bash meteor-test/peer/c/regenerate.sh --check

# Python Thrift：使用 Thrift 0.25.0
thrift --gen py -out meteor-test/peer/python/thrift/generated meteor-test/resource/protocol/thrift/order.thrift

# Node：在宿主执行，需要已构建的工具链镜像
bash meteor-test/peer/node/connect/generate.sh
bash meteor-test/peer/node/thrift/versions/regenerate.sh
bash meteor-test/peer/node/grpcweb/versions/regenerate.sh

# 当前版 gRPC-Web：生成器需在 PATH 中，版本见脚本头部
bash meteor-test/peer/node/grpcweb/generate.sh

# TLS 验收证书：需要 Python cryptography 和 JDK keytool
bash document/docs/other/testing/generate-tls-test-material.sh --module meteor-test
```

### gRPC-Web 1.5 生成契约

`peer/node/grpcweb/versions/generated/{binary,text}` 来自 `resource/protocol/proto/grpc/web.proto`，分别采用 `grpcweb`、`grpcwebtext` 模式；配套运行依赖为 `grpc-web@1.5.0`、`google-protobuf@3.21.4`。

[regenerate.sh](peer/node/grpcweb/versions/regenerate.sh) 固定使用 `protoc 3.21.12`、`protoc-gen-js 3.21.4`、`protoc-gen-grpc-web 1.5.0`，校验 AMD64 / ARM64 官方资产的 SHA-256，并输出生成器、契约和产物哈希。`METEOR_TEST_IMAGE_NATIVE` 可指定提供对应 protoc 的镜像，工具及中间产物位于 `build/sdk-compatibility/grpcweb-legacy-codegen`。

旧运行库搭配 `protoc-gen-js 4.0.3` 的生成结果曾出现 `reader.readStringRequireUtf8 is not a function`，因此旧版绑定使用配套的 3.21.4 生成器。

历史离线记录：AMD64 下两种模式的 Unicode、负整数序列化与反序列化及浏览器 Bundle 构建通过；ARM64 仅核验下载资产，未执行生成或浏览器 RPC。

## 常见坑点

- 修改 Dockerfile、Python 依赖或 npm 锁文件后，用 `--rebuild` 更新镜像；普通启动复用已安装依赖。SDK 多版本必须隔离依赖并核对实际版本，不能仅凭目录名或日志横幅判断。
- 注册发现区分 Meteor 自动发现、测试器解析后传址、SDK 原生发现；只接收已解析地址的对端属于适配发现。SDK 只有消费者时，只记录对应方向。
- 容器中的 `localhost` 指向当前容器；跨容器调用使用服务 DNS，TLS 身份与访问名称匹配。固定端口、节点故障和本机控制场景使用专用节点及必要的排他调度。
- 并发场景不得使用共享端口、目录或注册身份。所有本工程产物归 `meteor-test/build`；容器退出、程序退出和 exec 中断分别记录，清理失败也计入失败。
- WSL 容器共享宿主墙钟，Nacos 回拨后的时间戳问题不能靠增加等待解决。校时脚本位于 `tools/clock`，用法见 [WSL 时钟](README.md#wsl-时钟)；控制状态位于 `build/meteor-test-control`，环境故障与 RPC 失败分开报告。

需要检查 Protobuf JSON 对负零的转换时，在 Node 测试容器运行期间执行：

```bash
docker exec meteor-test-node node /workspace/meteor/meteor-test/tools/diagnostics/node/types-negative-zero-probe.mjs
```
