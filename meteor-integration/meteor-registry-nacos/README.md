# Meteor Nacos 注册中心

## 介绍

`meteor-registry-nacos` 提供基于 Nacos 的服务注册、发现和治理存储，当前适配 Nacos 3.1.2。

1. Config 保存应用定义、服务定义、全局治理和服务治理。
2. Naming 保存在线实例、发布订阅关系及实例范围的治理数据。
3. 规则正文保存为 XML，启用状态保存在对应详情中。

## 用法

添加依赖：

```groovy
implementation project(':meteor-registry-nacos')
```

配置 Nacos 地址与命名空间：

```java
NacosRegistryConfiguration registry = new NacosRegistryConfiguration();
registry.

setAddress("nacos.example.com:8848");
registry.

setNamespace("production");
registry.

setGroup("meteor");

// 启用 Nacos 认证时设置。
registry.

setUsername(System.getenv("NACOS_USERNAME"));
        registry.

setPassword(System.getenv("NACOS_PASSWORD"));
        registry.

setManagementToken(System.getenv("METEOR_MANAGEMENT_TOKEN"));
```

| 配置                                | 默认值       | 说明                                |
|-------------------------------------|--------------|-------------------------------------|
| `address`                           | 必填         | 多个 Nacos 地址以逗号分隔           |
| `namespace`                         | 公共命名空间 | 填写已存在的命名空间 ID             |
| `group`                             | `meteor`     | 注册数据分组                        |
| `username` / `password`             | 空           | Nacos 认证凭据，成对配置            |
| `timeoutMillis`                     | `5000`       | Config 读取超时，单位毫秒，须大于零 |
| `refreshMillis`                     | `1000`       | 应用注册信息核对间隔，单位毫秒      |
| `publishHost`                       | 实际监听地址 | 对外发布的主机地址                  |
| `managementToken` / `tokenProvider` | 空           | Meteor 管理入口凭据                 |

应用需要访问 Nacos 服务端口和 SDK gRPC 端口，默认分别为 `8848`、`9848`。治理账户需要配置读写、原生锁及 Naming 管理写权限；`nacos.lock.max_expire_time` 至少为 `30000` 毫秒。Naming 元信息容量按实例的服务和端点规模配置，开发 Compose 使用 `-Dnacos.naming.service.metadata.length=65536`。

通过 Meteor 配置接入并发布服务，`EchoService` 为应用定义的业务接口：

```java
MetConfiguration configuration = new MetConfiguration();
configuration.

setApplicationName("orders-server");
configuration.

addExtension(registry);
configuration.

connector("tcp").

bind("0.0.0.0",2181).

protocol("rsf");

try(
MetContext context = configuration.buildContext()){
        context.

start();

MetBindInfo<EchoService> service = context.publisher() //
        .rsfService(EchoService.class, value -> "echo:" + value) //
        .register();

RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
    admin.

getServiceAdmin(service.getBindID()).

putMeta("team","orders");

// 在此保持应用运行，退出时关闭上下文。
}
```

消费者安装同一扩展，通过 `publisher().rsfService(EchoService.class).register()` 订阅服务，再用 Meteor RPC 客户端调用。独立管理程序可通过 `registry.buildAdmin()` 获取管理入口，并在结束时关闭。

实例治理使用 `admin.getServiceAdmin(serviceId, instanceId)`，目标实例及服务须已登记。服务级和全局治理可以提前配置。升级旧存储时使用新的 namespace/group，将原缩写键下需保留的治理数据迁移到完整单词键，将旧 JSON 规则转换为 XML，并重新登记应用和服务。

## 存储结构

### 节点清单

Config 的每项对应一个 `dataId`，Naming 的每项对应一个 `serviceName`，均使用配置的 namespace 和 group。

`{applicationName}`、`{serviceId}`、`{instanceId}` 在 Key 中使用可逆编码：ASCII 字母、数字和 `-` 保留，其余 UTF-8 字节写成 `_hh`。例如 `order.api` 编码为 `order_2eapi`。原始值保存在正文或引用中。编码后的实例 ID 最长 64 字符，Config dataId 最长 256 字符。

```text
Config（持久）
├── applications.{applicationName}                           应用定义
├── services.{serviceId}                                     服务定义及规则开关
├── governance.global.state                                  全局状态及规则开关
├── governance.global.metadata                               全局元信息
├── governance.global.rule.{ruleType}                        全局规则 XML
├── governance.services.{serviceId}.state                    服务状态覆盖
├── governance.services.{serviceId}.metadata                 服务元信息
├── governance.services.{serviceId}.rule.{ruleType}          服务规则 XML
├── index.applications.{bucketNumber}                        应用目录引用索引
└── index.services.{bucketNumber}                            服务目录引用索引

Naming（临时成员）
├── instances.{instanceId}                                   实例运行详情
├── applications.{applicationName}.instances                 应用下的实例引用
├── services.{serviceId}.providers                           服务提供者关系
├── services.{serviceId}.consumers                           服务消费者关系
├── governance.instances.{instanceId}                        实例治理
└── governance.services.{serviceId}.instances.{instanceId}   实例服务治理
```

`{ruleType}` 取值为 `SERVICE_ROUTE`、`METHOD_ROUTE`、`ARGS_ROUTE`、`FLOW_CONTROL`。`{bucketNumber}` 为 `Math.floorMod(id.hashCode(), 16)`，应用索引使用应用名，服务索引使用服务 ID。

### 主记录与关系

`applications.orders` 保存应用定义：

```json
{
  "applicationName": "orders"
}
```

`services.echo` 保存服务定义及规则开关：

```json
{
  "serviceId": "echo",
  "group": "default",
  "name": "EchoService",
  "version": "1.0.0",
  "type": "com.example.EchoService",
  "ruleEnabled": {"SERVICE_ROUTE": false}
}
```

Naming 成员使用真实 IP、`port=0`、编码后的实例 ID 作为 `clusterName`，并设置 `ephemeral=true`。业务 RPC 地址保存在成员关系的 `endpoints` 和 `addresses` 中。

Naming 的 metadata 为字符串 Map，结构如下：

| 位置                                                                                           | metadata 字段                                                  |
|------------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| `instances.{instanceId}`                                                                       | `instanceId`、`body`、`services`、`ruleEnabled`、`ruleCleared` |
| `applications.{applicationName}.instances`                                                     | `instanceId`                                                   |
| `services.{serviceId}.providers` / `consumers`                                                 | `instanceId`、`body`、`ruleEnabled`、`ruleCleared`             |
| `governance.instances.{instanceId}` / `governance.services.{serviceId}.instances.{instanceId}` | `instanceId`、`state`、`metadata`、`rule.{ruleType}`           |

实例 `body` 是包含身份、角色、阻断状态、监听地址和管理地址的 JSON 字符串。实例 `services` 保存服务 ID 到角色的引用，例如 `{"echo":"Provider"}`。关系 `body` 保存 `enabled`、`endpoints`、`addresses`、`usedProtocols`，例如：

```json
{
  "instanceId": "node-1",
  "body": "{\"enabled\":true,\"endpoints\":[],\"addresses\":[\"rsf://192.0.2.10:2181\"],\"usedProtocols\":[\"rsf\"]}",
  "ruleEnabled": "{\"SERVICE_ROUTE\":false}",
  "ruleCleared": "[]"
}
```

`endpoints` 的元素包含 `connectorName`、`transport`、`protocolName`、`protocol`、`scheme`、`contextPath`、`bindEnabled`、`configuredAddress`、`listenAddress`、`publishedAddress`。

应用和服务定义持久保留。实例运行信息、成员关系及实例治理随临时登记清理；每次进程启动使用独立实例 ID。服务端可能暂存退出成员的管理元信息，同一进程撤回后再次登记相同服务时可能恢复这些配置。

### 治理数据

Config 中的 `governance.services.echo.rule.SERVICE_ROUTE` 以 `xml` 类型保存完整正文：

```xml

<route>
    <return value='addresses'/>
</route>
```

Naming 治理成员中的 `rule.{ruleType}` 字段直接保存 XML 字符串。

| 治理范围    | 规则开关位置                                                                              |
|-------------|-------------------------------------------------------------------------------------------|
| 全局        | Config `governance.global.state` 的 `ruleEnabled`                                         |
| 服务        | Config `services.{serviceId}` 的 `ruleEnabled`                                            |
| 实例        | Naming `instances.{instanceId}` 成员 metadata 的 `ruleEnabled`                            |
| 实例 + 服务 | Naming `services.{serviceId}.providers` 或 `consumers` 目标成员 metadata 的 `ruleEnabled` |

`ruleEnabled` 按规则类型保存布尔值。禁用时保留正文，启用后使用本层正文或继承规则。`ruleCleared` 保存显式清空的规则类型：`rule=null` 表示恢复继承，`rule=""` 表示显式清空。Naming 将这两个字段保存为 JSON 字符串。

状态和元信息采用带版本的 JSON，Config 示例为：

```json
{
  "revision": "version-1",
  "data": {
    "enabled": false,
    "block": null
  }
}
```

```json
{
  "revision": "version-2",
  "data": {
    "metadata": {
      "team": "orders"
    }
  }
}
```

全局 `governance.global.state` 在顶层携带 `ruleEnabled`；Naming 的 `state` 和 `metadata` 保存相同结构的 JSON 字符串。

### 目录索引

`index.applications.{bucketNumber}` 和 `index.services.{bucketNumber}` 使用 JSONL，每行保存一条主记录引用。服务索引记录示例：

```jsonl
{"id":"echo","key":"services.echo"}
```

每个索引桶占用一条 Config，容量受单条配置上限约束。目录查询支持游标分页，`limit` 为 1～1000。
