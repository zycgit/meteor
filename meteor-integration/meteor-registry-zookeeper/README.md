# Meteor ZooKeeper 注册中心

## 介绍

`meteor-registry-zookeeper` 提供基于 ZooKeeper 的服务注册、发现和治理存储。应用、实例档案和服务定义使用持久节点，在线登记及实例治理使用临时节点。规则正文保存为 XML，启用状态保存在对应详情中。

## 用法

添加依赖：

```groovy
implementation project(':meteor-registry-zookeeper')
```

配置 ZooKeeper 地址及数据根目录：

```java
ZookeeperRegistryConfiguration registry = new ZookeeperRegistryConfiguration();
registry.

setAddress("zk-1.example.com:2181,zk-2.example.com:2181,zk-3.example.com:2181");
registry.

setRoot("/production/meteor");

// 启用 digest 认证时设置。
registry.

setUsername(System.getenv("ZOOKEEPER_USERNAME"));
        registry.

setPassword(System.getenv("ZOOKEEPER_PASSWORD"));
        registry.

setManagementToken(System.getenv("METEOR_MANAGEMENT_TOKEN"));
```

| 配置                                | 默认值       | 说明                                    |
|-------------------------------------|--------------|-----------------------------------------|
| `address`                           | 必填         | 集群成员以逗号分隔                      |
| `root`                              | `/meteor`    | 注册数据直接存放在此根目录下            |
| `username` / `password`             | 空           | digest 凭据，成对配置；用户名须避开冒号 |
| `timeoutMillis`                     | `5000`       | 连接等待时间，单位毫秒，须大于零        |
| `sessionTimeoutMillis`              | `15000`      | 请求的会话超时，实际值由服务端协商      |
| `refreshMillis`                     | `1000`       | 应用注册信息核对间隔，单位毫秒          |
| `publishHost`                       | 实际监听地址 | 对外发布的主机地址                      |
| `managementToken` / `tokenProvider` | 空           | Meteor 管理入口凭据                     |

节点 ACL 由部署环境配置。应用需要登记和撤销权限，管理端需要读取和治理写入权限。升级旧存储时使用新的空 `root`，迁移需要保留的治理配置，并重新登记应用和服务。

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

实例治理使用 `admin.getServiceAdmin(serviceId, instanceId)`，目标实例及服务须已登记；服务级和全局治理可以提前配置。

## 存储结构

### 节点清单

以下使用默认根目录 `/meteor`。路径中的 `{applicationName}`、`{instanceId}`、`{serviceId}`、`{protocolName}` 使用对应字段的 UTF-8 Base64 URL 编码，并去掉末尾补位 `=`。例如服务 ID `echo` 的详情路径为 `services/ZWNobw/info`，原始 ID 保存在正文中。

```text
/meteor
├── applications/{applicationName}
│   ├── info                                      [持久] 应用定义
│   └── instances/{instanceId}                    [持久] 应用下的实例档案
├── instances/{instanceId}
│   ├── info                                      [持久] 实例档案
│   ├── owner                                     [持久] 登记会话标识
│   ├── runtime                                   [临时] 实例运行详情及规则开关
│   ├── services/{serviceId}                      [临时] 实例服务详情及规则开关
│   └── governance
│       ├── instance
│       │   ├── state                             [临时] 实例状态覆盖
│       │   ├── metadata                          [临时] 实例元信息
│       │   └── {ruleType}                        [临时] 实例规则 XML
│       └── services/{serviceId}
│           ├── state                             [临时] 实例服务状态覆盖
│           ├── metadata                          [临时] 实例服务元信息
│           └── {ruleType}                        [临时] 实例服务规则 XML
├── services/{serviceId}
│   ├── info                                      [持久] 服务定义及规则开关
│   ├── providers/{instanceId}                    [临时] 提供者引用
│   ├── consumers/{instanceId}                    [临时] 消费者引用
│   ├── protocols/{protocolName}/{instanceId}     [临时] 协议索引
│   └── governance
│       ├── state                                 [持久] 服务状态覆盖
│       ├── metadata                              [持久] 服务元信息
│       └── {ruleType}                            [持久] 服务规则 XML
└── global/governance
    ├── state                                     [持久] 全局状态及规则开关
    ├── metadata                                  [持久] 全局元信息
    └── {ruleType}                                [持久] 全局规则 XML
```

`{ruleType}` 取值为 `SERVICE_ROUTE`、`METHOD_ROUTE`、`ARGS_ROUTE`、`FLOW_CONTROL`。目录为持久节点，临时叶子随应用会话结束或超时清理；服务撤销时清理对应关系和实例服务治理。

### 详情与引用

`services/ZWNobw/info` 保存服务定义及规则开关：

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

实例及服务关系的正文如下：

| 节点                                          | 正文                                                                              |
|-----------------------------------------------|-----------------------------------------------------------------------------------|
| `applications/{applicationName}/info`         | 应用名                                                                            |
| `instances/{instanceId}/info`                 | 实例 ID、应用名、IP、进程 ID、单元名称                                            |
| `instances/{instanceId}/runtime`              | 实例身份、角色、阻断状态、监听地址、管理地址及规则开关                            |
| `instances/{instanceId}/services/{serviceId}` | 服务定义、`role`、`enabled`、`endpoints`、`addresses`、`usedProtocols` 及规则开关 |
| `services/{serviceId}/providers/{instanceId}` | 实例 ID 引用                                                                      |
| `services/{serviceId}/consumers/{instanceId}` | 实例 ID 引用                                                                      |

提供者和消费者引用均采用以下 JSON：

```json
{
  "instanceId": "node-1"
}
```

实例档案持久保留，运行详情属于临时数据。实例服务详情关联对应服务定义，提供者和消费者目录按实例 ID 引用该关系。

### 治理数据

`services/ZWNobw/governance/SERVICE_ROUTE` 的完整正文为 XML：

```xml

<route>
    <return value='addresses'/>
</route>
```

各范围的规则开关集中在详情的 `ruleEnabled` 属性中：

| 治理范围    | 开关所在详情                                  |
|-------------|-----------------------------------------------|
| 全局        | `global/governance/state`                     |
| 服务        | `services/{serviceId}/info`                   |
| 实例        | `instances/{instanceId}/runtime`              |
| 实例 + 服务 | `instances/{instanceId}/services/{serviceId}` |

`ruleEnabled` 按规则类型保存布尔值。禁用时保留 XML，启用后使用本层正文或继承规则。空字节正文通过 `ruleCleared` 区分两种配置：包含该规则类型表示显式清空，省略表示恢复继承。

状态和元信息使用 JSON，例如：

```json
{
  "enabled": false,
  "block": null
}
```

```json
{
  "metadata": {
    "team": "orders"
  }
}
```

全局 `state` 在同一对象中携带 `ruleEnabled`。全局和服务治理持久保留，实例及实例服务治理随临时登记清理。
