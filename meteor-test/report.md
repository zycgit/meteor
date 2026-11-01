# Meteor RPC 测试报告

结果：失败。

skipError：`true`。

用例筛选：全部。

通过 7828 项，失败 1 项，跳过 0 项，已确认未执行 0 项。

详细结果：[summary.json](build/docker-runs/meteor-test/build/interoperability/summary.json)

## SDK 版本兼容

— 表示本轮未执行该方向，不表示 SDK 不支持。

| 语言 | 协议 | SDK | 版本 | 运行图 | SDK → Meteor | Meteor → SDK |
| --- | --- | --- | --- | --- | --- | --- |
| go | connect | Connect-Go | 1.19.1 | protobuf-http1 | 通过 | 通过 |
| go | connect | Connect-Go | 1.21.1 | protobuf-http1 | 通过 | 通过 |
| go | grpc | grpc-go | 1.84.0 | protobuf-http2 | 通过 | 通过 |
| go | grpc | grpc-go | 1.85.0 | protobuf-http2 | 通过 | 通过 |
| go | dubbo | Apache-Dubbo-Go | 1.5.9 | dubbo-hessian2 | 通过 | 通过 |
| go | dubbo | Apache-Dubbo-Go | 3.3.2 | dubbo-hessian2 | 通过 | 通过 |
| go | hprose | Hprose-Go | 2.0.6+incompatible | http1-hprose | 通过 | 通过 |
| go | hprose | Hprose-Go | 3.0.16 | http1-hprose | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.13.0 | binary-framed | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.14.2 | binary-framed | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.18.1 | binary-framed | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.25.0 | binary-framed | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.18.1 | uuid-binary-framed | 通过 | 通过 |
| go | thrift | Apache-Thrift | 0.25.0 | uuid-binary-framed | 通过 | 通过 |
| c | grpc | gRPC-Core | 1.51.1 | debian-baseline | 通过 | 通过 |
| c | grpc | gRPC-Core | 1.85.0 | upstream-latest | 通过 | 通过 |
| c | thrift | Apache-Thrift | 0.17.0 | debian-baseline | 通过 | 通过 |
| c | thrift | Apache-Thrift | 0.25.0 | upstream-latest | 通过 | 通过 |
| cpp | grpc | gRPC-Core | 1.51.1 | debian-baseline | 通过 | 通过 |
| cpp | grpc | gRPC-Core | 1.85.0 | upstream-latest | 通过 | 通过 |
| cpp | thrift | Apache-Thrift | 0.17.0 | debian-baseline | 通过 | 通过 |
| cpp | thrift | Apache-Thrift | 0.25.0 | upstream-latest | 通过 | 通过 |
| cpp | jsonrpc | libjson-rpc-cpp | 0.7.0 | legacy-debian | 通过 | 通过 |
| cpp | jsonrpc | libjson-rpc-cpp | 1.4.1 | upstream-latest | 通过 | 通过 |
| c | oncrpc | libtirpc | 1.3.8 | upstream-latest | 通过 | 通过 |
| cpp | oncrpc | libtirpc | 1.3.8 | upstream-latest | 通过 | 通过 |
| node | connect | Connect-ES | 1.7.0 | connect-1.7.0 | 通过 | 通过 |
| node | connect | Connect-ES | 2.3.0 | connect-2.3.0 | 通过 | 通过 |
| node | dubbo | dubbo2.js | 1.0.4 | dubbo-1.0.4 | 通过 | — |
| node | dubbo | dubbo2.js | 2.3.9 | dubbo-2.3.9 | 通过 | — |
| node | jsonrpc | Jayson | 2.1.2 | jsonrpc-2.1.2 | 通过 | 通过 |
| node | jsonrpc | Jayson | 3.7.0 | jsonrpc-3.7.0 | 通过 | 通过 |
| node | jsonrpc | Jayson | 4.3.0 | jsonrpc-4.3.0 | 通过 | 通过 |
| node | jsonrpc | Jayson | 5.0.0 | jsonrpc-5.0.0 | 通过 | 通过 |
| node | soap | node-soap | 0.45.0 | soap-0.45.0 | 通过 | 通过 |
| node | soap | node-soap | 1.14.0 | soap-1.14.0 | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-binary-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-binary-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-binary-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-compact-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-compact-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-compact-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-json-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-json-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.12.0 | thrift-0.12.0-json-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-binary-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-binary-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-binary-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-compact-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-compact-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-compact-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-json-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-json-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.14.2 | thrift-0.14.2-json-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-binary-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-binary-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-binary-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-compact-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-compact-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-compact-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-json-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-json-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.23.0 | thrift-0.23.0-json-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-binary-bigint-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-binary-bigint-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-binary-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-binary-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-binary-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-compact-bigint-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-compact-bigint-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-compact-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-compact-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-compact-string | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-json-bigint-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-json-bigint-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-json-i64-max | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-json-i64-min | 通过 | 通过 |
| node | thrift | Apache Thrift | 0.25.0 | thrift-0.25.0-json-string | 通过 | 通过 |
| c | xmlrpc | xmlrpc-c | 1.33.14 | legacy-debian | 通过 | 通过 |
| c | xmlrpc | xmlrpc-c | 1.64.04 | upstream-latest | 通过 | 通过 |
| cpp | xmlrpc | xmlrpc-c | 1.33.14 | legacy-debian | 通过 | 通过 |
| cpp | xmlrpc | xmlrpc-c | 1.64.04 | upstream-latest | 通过 | 通过 |
| java | connect | Connect Kotlin | 0.10.0 | connectCurrent | 通过 | — |
| java | connect | Connect Kotlin | 0.4.0 | connectLegacy | 通过 | — |
| java | connect | Connect Kotlin | 0.5.1 | connect | 通过 | — |
| java | dubbo | Alibaba Dubbo | 2.6.12 | dubboClassic | 通过 | 通过 |
| java | dubbo | Apache Dubbo | 2.7.23 | dubboLegacy | 通过 | 通过 |
| java | dubbo | Apache Dubbo | 3.3.6 | dubbo | 通过 | 通过 |
| java | grpc | gRPC-Java | 1.85.1 | grpcCurrent | 通过 | 通过 |
| java | grpcweb | Connect Kotlin | 0.10.0 | connectCurrent | 通过 | — |
| java | grpcweb | Connect Kotlin | 0.4.0 | connectLegacy | 通过 | — |
| java | grpcweb | Connect Kotlin | 0.5.1 | connect | 通过 | — |
| java | hessian | Caucho Hessian | 4.0.51 | hessianLegacy | 通过 | 通过 |
| java | hessian | Caucho Hessian | 4.0.66 | hessianRecent | 通过 | 通过 |
| java | hprose | Hprose Java | 2.0.38 | hprose | 通过 | 通过 |
| java | jsonrpc | jsonrpc4j | 1.7 | jsonrpc | 通过 | 通过 |
| java | oncrpc | RemoteTea | 1.1.6 | oncrpc | 通过 | 通过 |
| java | soap | Metro JAX-WS | 2.3.7 | soapJavax | 通过 | 通过 |
| java | soap | Metro JAX-WS | 3.0.2 | soapLegacy | 通过 | 通过 |
| java | soap | Metro JAX-WS | 4.0.5 | soap | 通过 | 通过 |
| java | thrift | Apache Thrift | 0.10.0 | thriftClassic | 通过 | 通过 |
| java | thrift | Apache Thrift | 0.13.0 | thriftPreLayered | 通过 | 通过 |
| java | thrift | Apache Thrift | 0.16.0 | thriftLayered | 通过 | 通过 |
| java | thrift | Apache Thrift | 0.22.0 | thriftLegacy | 通过 | 通过 |
| java | thrift | Apache Thrift | 0.25.0 | thrift | 通过 | 通过 |
| java | triple | Apache Dubbo | 3.2.20 | tripleLegacy | 通过 | 通过 |
| java | triple | Apache Dubbo | 3.3.6 | dubbo | 通过 | 通过 |
| java | xmlrpc | Apache XML-RPC | 3.1.3 | xmlrpc | 通过 | 通过 |
| java | connect | Connect RPC Java | 0.2.1 | connectServer | — | 通过 |
| python | connect | connectrpc | 0.10.1 | google | 通过 | 通过 |
| python | connect | connectrpc | 0.11.1 | google | 通过 | 通过 |
| python | connect | connectrpc | 0.11.1 | native | 通过 | 通过 |
| python | connect | connectrpc | 0.13.0 | google | 通过 | 通过 |
| python | connect | connectrpc | 0.13.0 | native | 通过 | 通过 |
| python | grpc-web | connectrpc | 0.10.1 | google | 通过 | 通过 |
| python | grpc-web | connectrpc | 0.11.1 | google | 通过 | 通过 |
| python | grpc-web | connectrpc | 0.11.1 | native | 通过 | 通过 |
| python | grpc-web | connectrpc | 0.13.0 | google | 通过 | 通过 |
| python | grpc-web | connectrpc | 0.13.0 | native | 通过 | 通过 |
| python | grpc | grpcio | 1.85.0 | protobuf | 通过 | 通过 |
| browser | grpc-web | grpc-web | 1.5.0 | grpcweb-1.5.0-binary | 通过 | — |
| browser | grpc-web | grpc-web | 1.5.0 | grpcweb-1.5.0-text | 通过 | — |
| browser | grpc-web | grpc-web | 2.1.1 | grpcweb-2.1.1-binary | 通过 | — |
| browser | grpc-web | grpc-web | 2.1.1 | grpcweb-2.1.1-text | 通过 | — |
| c | soap | gSOAP | 2.8.124 | debian-baseline | 通过 | 通过 |
| c | soap | gSOAP | 2.8.146 | upstream-latest | 通过 | 通过 |
| cpp | soap | gSOAP | 2.8.124 | debian-baseline | 通过 | 通过 |
| cpp | soap | gSOAP | 2.8.146 | upstream-latest | 通过 | 通过 |
| python | soap | soapfish2 | 0.7.4 | soap11 | — | 通过 |
| python | soap | zeep | 3.4.0 | soap11 | 通过 | — |
| python | soap | zeep | 4.3.3 | soap11 | 通过 | — |
| python | thrift | Apache-Thrift | 0.13.0 | binary-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.13.0 | compact-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.13.0 | json-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.16.0 | binary-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.16.0 | compact-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.16.0 | json-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.25.0 | binary-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.25.0 | compact-framed | 通过 | 通过 |
| python | thrift | Apache-Thrift | 0.25.0 | json-framed | 通过 | 通过 |

## 失败用例

- `net.hasor.realmeteor.registry.dubbo.DubboRegistryDiscoveryScenarioTest#dubbo27ProviderShutdownWithdrawsDiscoveredAddress`

      java.lang.AssertionError: Official Dubbo peer shutdown timed out:
      NOTE: Picked up JDK_JAVA_OPTIONS: "-Djava.io.tmpdir=/workspace/meteor/meteor-test/build/groups/g13/process-evidence/meteor-peer-484ccf21-de6a-4718-9a99-21a950707e9b/tmp" "-Duser.home=/workspace/meteor/meteor-test/build/groups/g13/process-evidence/meteor-peer-484ccf21-de6a-4718-9a99-21a950707e9b/tmp/home"
      Picked up JAVA_TOOL_OPTIONS: -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8

      No such logging.properties in classpath for jdk logging config!
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No value is configured in the registry, the DynamicConfigurationFactory extension[name : zookeeper] supports as the config center, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] The registry[<dubbo:registry address="zookeeper://zookeeper-native.meteor.test:2181" port="2181" protocol="zookeeper" check="true" />] will be used as the config center, dubbo version: 2.7.23, current host: 172.22.0.14
      SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
      SLF4J: Defaulting to no-operation (NOP) logger implementation
      SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
      SLF4J: Failed to load class "org.slf4j.impl.StaticMDCBinder".
      SLF4J: Defaulting to no-operation MDCAdapter implementation.
      SLF4J: See http://www.slf4j.org/codes.html#no_static_mdc_binder for further details.
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No valid zookeeper client found from cache, therefore create a new client for url. zookeeper://zookeeper-native.meteor.test:2181/ConfigCenterConfig?check=true&config-file=dubbo.properties&group=dubbo&highest-priority=false&register-mode=interface&register.mode=interface&timeout=3000, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Curator zookeeper client instance initiated successfully, session id is 100178c0dbe0037, dubbo version: 2.7.23, current host: 172.22.0.14
      No such logging.properties in classpath for jdk logging config!
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No global configuration in config center, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No application level configuration in config center, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No valid monitor config found, specify monitor info to enable collection of Dubbo statistics, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No value is configured in the registry, the MetadataReportFactory extension[name : zookeeper] supports as the metadata center, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] The registry[<dubbo:registry address="zookeeper://zookeeper-native.meteor.test:2181" port="2181" protocol="zookeeper" check="true" />] will be used as the metadata center, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] find valid zookeeper client from the cache for address: zookeeper://zookeeper-native.meteor.test:2181/org.apache.dubbo.metadata.report.MetadataReport?application=meteor-dubbo-official-peer&client=&port=2181&protocol=zookeeper&register-mode=interface&register.mode=interface, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] DubboBootstrap has been initialized!, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] DubboBootstrap is starting..., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No valid ip found from environment, try to get local host., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Register dubbo service net.hasor.example.protocol.dubbo.interop.DubboInteropService url dubbo://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&bind.ip=172.22.0.14&bind.port=32807&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&qos.enable=false&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0 to registry registry://zookeeper-native.meteor.test:2181/org.apache.dubbo.registry.RegistryService?application=meteor-dubbo-official-peer&check=true&dubbo=2.0.2&logger=jdk&metadata-type=local&pid=17817&qos.enable=false&register-mode=interface&register.mode=interface&registry=zookeeper&release=2.7.23&timestamp=1793518681963, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] qos won't be started because it is disabled. Please check dubbo.application.qos.enable is configured either in system property, dubbo.properties or XML/spring-boot configuration., dubbo version: 2.7.23, current host: 172.22.0.14
      log4j:WARN No appenders could be found for logger (io.netty.util.internal.logging.InternalLoggerFactory).
      log4j:WARN Please initialize the log4j system properly.
      log4j:WARN See http://logging.apache.org/log4j/1.2/faq.html#noconfig for more info.
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Start NettyServer bind /0.0.0.0:32807, export java-sdk.meteor.test/172.22.0.14:32807, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] find valid zookeeper client from the cache for address: zookeeper://zookeeper-native.meteor.test:2181/org.apache.dubbo.registry.RegistryService?application=meteor-dubbo-official-peer&check=true&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&logger=jdk&metadata-type=local&pid=17817&qos.enable=false&register-mode=interface&register.mode=interface&release=2.7.23&timestamp=1793518681963, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Register: dubbo://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Subscribe: provider://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&bind.ip=172.22.0.14&bind.port=32807&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&qos.enable=false&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Notify urls for subscribe url provider://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&bind.ip=172.22.0.14&bind.port=32807&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&qos.enable=false&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0, urls: [empty://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&bind.ip=172.22.0.14&bind.port=32807&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&qos.enable=false&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0], dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Listening for migration rules on dataId-meteor-dubbo-official-peer.migration group-MIGRATION, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Using the following migration rule to migrate:, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] INIT, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] store provider metadata. Identifier : org.apache.dubbo.metadata.report.identifier.MetadataIdentifier@3265b0e5; definition: FullServiceDefinition{parameters={side=provider, service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0, release=2.7.23, methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice, logger=jdk, deprecated=false, dubbo=2.0.2, interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService, version=1.0.0, qos.enable=false, generic=false, revision=1.0.0, serialization=hessian2, metadata-type=local, application=meteor-dubbo-official-peer, scope=remote, dynamic=true, ssl-enabled=false, group=default, anyhost=true, register=true}} ServiceDefinition [canonicalName=net.hasor.example.protocol.dubbo.interop.DubboInteropService, codeSource=file:/workspace/meteor/meteor-test/peer/java/contracts/build/classes/java/main/, methods=[MethodDefinition [name=properties, parameterTypes=[java.util.Map<java.lang.String,java.lang.Object>], returnType=java.util.Map<java.lang.String,java.lang.Object>], MethodDefinition [name=combine, parameterTypes=[java.lang.String, int], returnType=java.lang.String], MethodDefinition [name=fail, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=echo, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=metadata, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=slow, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=notice, parameterTypes=[java.lang.String], returnType=void], MethodDefinition [name=binary, parameterTypes=[byte[]], returnType=byte[]], MethodDefinition [name=person, parameterTypes=[net.hasor.example.protocol.dubbo.interop.DubboInteropPerson], returnType=net.hasor.example.protocol.dubbo.interop.DubboInteropPerson], MethodDefinition [name=people, parameterTypes=[java.util.List<net.hasor.example.protocol.dubbo.interop.DubboInteropPerson>], returnType=java.util.List<net.hasor.example.protocol.dubbo.interop.DubboInteropPerson>], MethodDefinition [name=overloaded, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=overloaded, parameterTypes=[int], returnType=java.lang.String], MethodDefinition [name=noArgs, parameterTypes=[], returnType=java.lang.String], MethodDefinition [name=nullable, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=nothing, parameterTypes=[], returnType=void], MethodDefinition [name=notices, parameterTypes=[], returnType=int]]], dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] DubboBootstrap is ready., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] DubboBootstrap has started., dubbo version: 2.7.23, current host: 172.22.0.14
      READY 32807
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] The connection of /172.22.0.6:41172 -> /172.22.0.14:32807 is established., dubbo version: 2.7.23, current host: 172.22.0.14
      java.lang.reflect.InaccessibleObjectException: Unable to make field final int java.math.BigInteger.signum accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      java.lang.reflect.InaccessibleObjectException: Unable to make field final int[] java.math.BigInteger.mag accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      java.lang.reflect.InaccessibleObjectException: Unable to make field private int java.math.BigInteger.bitCountPlusOne accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      java.lang.reflect.InaccessibleObjectException: Unable to make field private int java.math.BigInteger.bitLengthPlusOne accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      java.lang.reflect.InaccessibleObjectException: Unable to make field private int java.math.BigInteger.lowestSetBitPlusTwo accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      java.lang.reflect.InaccessibleObjectException: Unable to make field private int java.math.BigInteger.firstNonzeroIntNumPlusTwo accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Unregister: dubbo://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Unsubscribe: provider://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&bind.ip=172.22.0.14&bind.port=32807&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&qos.enable=false&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Waiting 1000ms for registry to notify all consumers before unexport. Usually, this is called when you use dubbo API, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Dubbo Service has been destroyed., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Close all registries [zookeeper://zookeeper-native.meteor.test:2181/org.apache.dubbo.registry.RegistryService?application=meteor-dubbo-official-peer&check=true&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&logger=jdk&metadata-type=local&pid=17817&qos.enable=false&register-mode=interface&register.mode=interface&release=2.7.23&timestamp=1793518681963], dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Destroy registry:zookeeper://zookeeper-native.meteor.test:2181/org.apache.dubbo.registry.RegistryService?application=meteor-dubbo-official-peer&check=true&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&logger=jdk&metadata-type=local&pid=17817&qos.enable=false&register-mode=interface&register.mode=interface&release=2.7.23&timestamp=1793518681963, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Close dubbo server: java-sdk.meteor.test/172.22.0.14:32807, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:15 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Close NettyServer bind /0.0.0.0:32807, export java-sdk.meteor.test/172.22.0.14:32807, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:15 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Close netty channel [id: 0x891e3755, L:/172.22.0.14:32807 - R:/172.22.0.6:41172], dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:15 AM org.apache.dubbo.common.logger.jdk.JdkLogger warn
      WARNING:  [DUBBO] All clients has disconnected from /172.22.0.14:32807. You can graceful shutdown now., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:15 AM org.apache.dubbo.common.logger.jdk.JdkLogger warn
      WARNING:  [DUBBO] No available executors, this is not expected, framework should call createExecutorIfAbsent first before coming to here., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:15 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] The connection of /172.22.0.6:41172 -> /172.22.0.14:32807 is disconnected., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:10 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] qos-server stopped., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:10 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] DubboBootstrap is about to shutdown..., dubbo version: 2.7.23, current host: 172.22.0.14

          at net.hasor.realmeteor.protocol.dubbo.DubboProcess.close(DubboProcess.java:149)
          at net.hasor.realmeteor.registry.dubbo.DubboRegistryDiscoveryScenarioTest.dubbo27ProviderShutdownWithdrawsDiscoveredAddress(DubboRegistryDiscoveryScenarioTest.java:155)
          Suppressed: java.lang.AssertionError: Official Dubbo peer failed:
      NOTE: Picked up JDK_JAVA_OPTIONS: "-Djava.io.tmpdir=/workspace/meteor/meteor-test/build/groups/g13/process-evidence/meteor-peer-484ccf21-de6a-4718-9a99-21a950707e9b/tmp" "-Duser.home=/workspace/meteor/meteor-test/build/groups/g13/process-evidence/meteor-peer-484ccf21-de6a-4718-9a99-21a950707e9b/tmp/home"
      Picked up JAVA_TOOL_OPTIONS: -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8

      No such logging.properties in classpath for jdk logging config!
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No value is configured in the registry, the DynamicConfigurationFactory extension[name : zookeeper] supports as the config center, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] The registry[<dubbo:registry address="zookeeper://zookeeper-native.meteor.test:2181" port="2181" protocol="zookeeper" check="true" />] will be used as the config center, dubbo version: 2.7.23, current host: 172.22.0.14
      SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
      SLF4J: Defaulting to no-operation (NOP) logger implementation
      SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
      SLF4J: Failed to load class "org.slf4j.impl.StaticMDCBinder".
      SLF4J: Defaulting to no-operation MDCAdapter implementation.
      SLF4J: See http://www.slf4j.org/codes.html#no_static_mdc_binder for further details.
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No valid zookeeper client found from cache, therefore create a new client for url. zookeeper://zookeeper-native.meteor.test:2181/ConfigCenterConfig?check=true&config-file=dubbo.properties&group=dubbo&highest-priority=false&register-mode=interface&register.mode=interface&timeout=3000, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Curator zookeeper client instance initiated successfully, session id is 100178c0dbe0037, dubbo version: 2.7.23, current host: 172.22.0.14
      No such logging.properties in classpath for jdk logging config!
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No global configuration in config center, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No application level configuration in config center, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No valid monitor config found, specify monitor info to enable collection of Dubbo statistics, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No value is configured in the registry, the MetadataReportFactory extension[name : zookeeper] supports as the metadata center, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] The registry[<dubbo:registry address="zookeeper://zookeeper-native.meteor.test:2181" port="2181" protocol="zookeeper" check="true" />] will be used as the metadata center, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] find valid zookeeper client from the cache for address: zookeeper://zookeeper-native.meteor.test:2181/org.apache.dubbo.metadata.report.MetadataReport?application=meteor-dubbo-official-peer&client=&port=2181&protocol=zookeeper&register-mode=interface&register.mode=interface, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] DubboBootstrap has been initialized!, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] DubboBootstrap is starting..., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:01 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] No valid ip found from environment, try to get local host., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Register dubbo service net.hasor.example.protocol.dubbo.interop.DubboInteropService url dubbo://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&bind.ip=172.22.0.14&bind.port=32807&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&qos.enable=false&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0 to registry registry://zookeeper-native.meteor.test:2181/org.apache.dubbo.registry.RegistryService?application=meteor-dubbo-official-peer&check=true&dubbo=2.0.2&logger=jdk&metadata-type=local&pid=17817&qos.enable=false&register-mode=interface&register.mode=interface&registry=zookeeper&release=2.7.23&timestamp=1793518681963, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] qos won't be started because it is disabled. Please check dubbo.application.qos.enable is configured either in system property, dubbo.properties or XML/spring-boot configuration., dubbo version: 2.7.23, current host: 172.22.0.14
      log4j:WARN No appenders could be found for logger (io.netty.util.internal.logging.InternalLoggerFactory).
      log4j:WARN Please initialize the log4j system properly.
      log4j:WARN See http://logging.apache.org/log4j/1.2/faq.html#noconfig for more info.
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Start NettyServer bind /0.0.0.0:32807, export java-sdk.meteor.test/172.22.0.14:32807, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] find valid zookeeper client from the cache for address: zookeeper://zookeeper-native.meteor.test:2181/org.apache.dubbo.registry.RegistryService?application=meteor-dubbo-official-peer&check=true&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&logger=jdk&metadata-type=local&pid=17817&qos.enable=false&register-mode=interface&register.mode=interface&release=2.7.23&timestamp=1793518681963, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Register: dubbo://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Subscribe: provider://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&bind.ip=172.22.0.14&bind.port=32807&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&qos.enable=false&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Notify urls for subscribe url provider://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&bind.ip=172.22.0.14&bind.port=32807&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&qos.enable=false&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0, urls: [empty://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&bind.ip=172.22.0.14&bind.port=32807&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&qos.enable=false&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0], dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Listening for migration rules on dataId-meteor-dubbo-official-peer.migration group-MIGRATION, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Using the following migration rule to migrate:, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] INIT, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] store provider metadata. Identifier : org.apache.dubbo.metadata.report.identifier.MetadataIdentifier@3265b0e5; definition: FullServiceDefinition{parameters={side=provider, service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0, release=2.7.23, methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice, logger=jdk, deprecated=false, dubbo=2.0.2, interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService, version=1.0.0, qos.enable=false, generic=false, revision=1.0.0, serialization=hessian2, metadata-type=local, application=meteor-dubbo-official-peer, scope=remote, dynamic=true, ssl-enabled=false, group=default, anyhost=true, register=true}} ServiceDefinition [canonicalName=net.hasor.example.protocol.dubbo.interop.DubboInteropService, codeSource=file:/workspace/meteor/meteor-test/peer/java/contracts/build/classes/java/main/, methods=[MethodDefinition [name=properties, parameterTypes=[java.util.Map<java.lang.String,java.lang.Object>], returnType=java.util.Map<java.lang.String,java.lang.Object>], MethodDefinition [name=combine, parameterTypes=[java.lang.String, int], returnType=java.lang.String], MethodDefinition [name=fail, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=echo, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=metadata, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=slow, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=notice, parameterTypes=[java.lang.String], returnType=void], MethodDefinition [name=binary, parameterTypes=[byte[]], returnType=byte[]], MethodDefinition [name=person, parameterTypes=[net.hasor.example.protocol.dubbo.interop.DubboInteropPerson], returnType=net.hasor.example.protocol.dubbo.interop.DubboInteropPerson], MethodDefinition [name=people, parameterTypes=[java.util.List<net.hasor.example.protocol.dubbo.interop.DubboInteropPerson>], returnType=java.util.List<net.hasor.example.protocol.dubbo.interop.DubboInteropPerson>], MethodDefinition [name=overloaded, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=overloaded, parameterTypes=[int], returnType=java.lang.String], MethodDefinition [name=noArgs, parameterTypes=[], returnType=java.lang.String], MethodDefinition [name=nullable, parameterTypes=[java.lang.String], returnType=java.lang.String], MethodDefinition [name=nothing, parameterTypes=[], returnType=void], MethodDefinition [name=notices, parameterTypes=[], returnType=int]]], dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO: using logger: org.apache.dubbo.common.logger.jdk.JdkLoggerAdapter
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] DubboBootstrap is ready., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:02 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] DubboBootstrap has started., dubbo version: 2.7.23, current host: 172.22.0.14
      READY 32807
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] The connection of /172.22.0.6:41172 -> /172.22.0.14:32807 is established., dubbo version: 2.7.23, current host: 172.22.0.14
      java.lang.reflect.InaccessibleObjectException: Unable to make field final int java.math.BigInteger.signum accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      java.lang.reflect.InaccessibleObjectException: Unable to make field final int[] java.math.BigInteger.mag accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      java.lang.reflect.InaccessibleObjectException: Unable to make field private int java.math.BigInteger.bitCountPlusOne accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      java.lang.reflect.InaccessibleObjectException: Unable to make field private int java.math.BigInteger.bitLengthPlusOne accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      java.lang.reflect.InaccessibleObjectException: Unable to make field private int java.math.BigInteger.lowestSetBitPlusTwo accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      java.lang.reflect.InaccessibleObjectException: Unable to make field private int java.math.BigInteger.firstNonzeroIntNumPlusTwo accessible: module java.base does not "opens java.math" to unnamed module @5ccd43c2
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:354)
          at java.base/java.lang.reflect.AccessibleObject.checkCanSetAccessible(AccessibleObject.java:297)
          at java.base/java.lang.reflect.Field.checkCanSetAccessible(Field.java:178)
          at java.base/java.lang.reflect.Field.setAccessible(Field.java:172)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.getFieldMap(JavaDeserializer.java:340)
          at com.alibaba.com.caucho.hessian.io.JavaDeserializer.<init>(JavaDeserializer.java:80)
          at com.alibaba.com.caucho.hessian.io.BigIntegerDeserializer.<init>(BigIntegerDeserializer.java:25)
          at com.alibaba.com.caucho.hessian.io.SerializerFactory.<clinit>(SerializerFactory.java:142)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.DefaultHessian2FactoryInitializer.createSerializerFactory(DefaultHessian2FactoryInitializer.java:26)
          at org.apache.dubbo.common.serialize.hessian2.dubbo.AbstractHessian2FactoryInitializer.getSerializerFactory(AbstractHessian2FactoryInitializer.java:30)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2ObjectInput.<init>(Hessian2ObjectInput.java:38)
          at org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization.deserialize(Hessian2Serialization.java:56)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:108)
          at org.apache.dubbo.rpc.protocol.dubbo.DecodeableRpcInvocation.decode(DecodeableRpcInvocation.java:83)
          at org.apache.dubbo.remoting.transport.DecodeHandler.decode(DecodeHandler.java:57)
          at org.apache.dubbo.remoting.transport.DecodeHandler.received(DecodeHandler.java:44)
          at org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.run(ChannelEventRunnable.java:57)
          at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
          at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
          at org.apache.dubbo.common.threadlocal.InternalRunnable.run(InternalRunnable.java:41)
          at java.base/java.lang.Thread.run(Thread.java:840)
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Unregister: dubbo://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Unsubscribe: provider://java-sdk.meteor.test:32807/net.hasor.example.protocol.dubbo.interop.DubboInteropService?anyhost=true&application=meteor-dubbo-official-peer&bind.ip=172.22.0.14&bind.port=32807&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=default&interface=net.hasor.example.protocol.dubbo.interop.DubboInteropService&logger=jdk&metadata-type=local&methods=metadata,nullable,nothing,echo,overloaded,people,noArgs,notices,fail,slow,person,binary,properties,combine,notice&pid=17817&qos.enable=false&register=true&release=2.7.23&revision=1.0.0&scope=remote&serialization=hessian2&service.name=ServiceBean:default/net.hasor.example.protocol.dubbo.interop.DubboInteropService:1.0.0&side=provider&ssl-enabled=false&timestamp=1793518681974&version=1.0.0, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Waiting 1000ms for registry to notify all consumers before unexport. Usually, this is called when you use dubbo API, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Dubbo Service has been destroyed., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Close all registries [zookeeper://zookeeper-native.meteor.test:2181/org.apache.dubbo.registry.RegistryService?application=meteor-dubbo-official-peer&check=true&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&logger=jdk&metadata-type=local&pid=17817&qos.enable=false&register-mode=interface&register.mode=interface&release=2.7.23&timestamp=1793518681963], dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Destroy registry:zookeeper://zookeeper-native.meteor.test:2181/org.apache.dubbo.registry.RegistryService?application=meteor-dubbo-official-peer&check=true&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&logger=jdk&metadata-type=local&pid=17817&qos.enable=false&register-mode=interface&register.mode=interface&release=2.7.23&timestamp=1793518681963, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:11 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Close dubbo server: java-sdk.meteor.test/172.22.0.14:32807, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:15 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Close NettyServer bind /0.0.0.0:32807, export java-sdk.meteor.test/172.22.0.14:32807, dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:15 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] Close netty channel [id: 0x891e3755, L:/172.22.0.14:32807 - R:/172.22.0.6:41172], dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:15 AM org.apache.dubbo.common.logger.jdk.JdkLogger warn
      WARNING:  [DUBBO] All clients has disconnected from /172.22.0.14:32807. You can graceful shutdown now., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:15 AM org.apache.dubbo.common.logger.jdk.JdkLogger warn
      WARNING:  [DUBBO] No available executors, this is not expected, framework should call createExecutorIfAbsent first before coming to here., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:15 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] The connection of /172.22.0.6:41172 -> /172.22.0.14:32807 is disconnected., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:10 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] qos-server stopped., dubbo version: 2.7.23, current host: 172.22.0.14
      Nov 01, 2026 7:38:10 AM org.apache.dubbo.common.logger.jdk.JdkLogger info
      INFO:  [DUBBO] DubboBootstrap is about to shutdown..., dubbo version: 2.7.23, current host: 172.22.0.14

              at net.hasor.realmeteor.protocol.dubbo.DubboProcess.close(DubboProcess.java:153)
              at net.hasor.realmeteor.registry.dubbo.DubboRegistryDiscoveryScenarioTest.dubbo27ProviderShutdownWithdrawsDiscoveredAddress(DubboRegistryDiscoveryScenarioTest.java:140)


## 汇总异常

- Group g13 exited with 1
- Group g13 JVM process exited unsuccessfully: /workspace/meteor/meteor-test/build/groups/g13/process
- The aggregate contains failed or skipped testcases

完整日志与 XML：[本轮归档](build/docker-runs)
