# Meteor JDBC 注册中心

## 介绍

`meteor-registry-jdbc` 提供基于关系数据库的服务注册、发现和治理存储。应用、实例和服务分别保存在业务表中，规则正文直接保存 XML，规则开关归入对应详情。

模块提供 H2、MySQL、PostgreSQL、Oracle、SQL Server 建表脚本；当前运行测试覆盖 H2，其余数据库兼容性待验收。

## 用法

添加依赖：

```groovy
implementation project(':meteor-registry-jdbc')
```

先执行 `META-INF/meteor/schema/` 中对应数据库的建表脚本：

| 数据库     | 脚本             |
|------------|------------------|
| H2         | `h2.sql`         |
| MySQL      | `mysql.sql`      |
| PostgreSQL | `postgresql.sql` |
| Oracle     | `oracle.sql`     |
| SQL Server | `sqlserver.sql`  |

更换集群时创建新数据库，并执行对应的建表脚本。

应用提供已配置地址及认证信息的 `DataSource`，并管理其生命周期：

```java
JdbcRegistryConfiguration registry = new JdbcRegistryConfiguration();
registry.

setDataSource(applicationDataSource);
registry.

setManagementToken(System.getenv("METEOR_MANAGEMENT_TOKEN"));
        registry.

setLeaseMillis(15000);

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

`EchoService` 为应用定义的业务接口。消费者安装同一扩展，通过 `publisher().rsfService(EchoService.class).register()` 订阅服务，再用 Meteor RPC 客户端调用。独立管理程序可通过 `registry.buildAdmin()` 获取管理入口，并在结束时关闭。

| 配置                                | 默认值       | 说明                               |
|-------------------------------------|--------------|------------------------------------|
| `dataSource`                        | 必填         | 已配置数据库地址和认证信息的数据源 |
| `leaseMillis`                       | `15000`      | 登记会话租期，单位毫秒，最小 `300` |
| `refreshMillis`                     | `1000`       | 应用注册信息核对间隔，单位毫秒     |
| `publishHost`                       | 实际监听地址 | 对外发布的主机地址                 |
| `managementToken` / `tokenProvider` | 空           | Meteor 管理入口凭据                |

访问同一注册库的应用节点需保持系统时钟同步。实例治理使用 `admin.getServiceAdmin(serviceId, instanceId)`，目标实例及服务须已登记；服务级和全局治理可以提前配置。

## 存储结构

### 业务表

| 表                         | 主键                                     | 内容与生命周期                                                     |
|----------------------------|------------------------------------------|--------------------------------------------------------------------|
| `meteor_applications`      | `application_id`                         | 应用档案，持久                                                     |
| `meteor_instances`         | `instance_id`                            | 实例档案、所属应用及单元，最近登记会话、当前运行详情及实例规则开关 |
| `meteor_services`          | `service_id`                             | 服务定义及服务规则开关，持久                                       |
| `meteor_instance_services` | `instance_id, service_id`                | 实例发布或订阅的服务详情、成员角色及交叉范围规则开关，租约内可见   |
| `meteor_member_protocols`  | `service_id, instance_id, protocol_name` | 成员协议名称，供服务协议汇总，租约内可见                           |
| `meteor_governance`        | `instance_id, service_id, item_name`     | 各范围的状态、元信息和规则正文                                     |
| `meteor_sessions`          | `session_id`                             | 登记会话及到期时间 `expires_at`，时间单位为 UTC 毫秒               |

以上七张均为普通数据库表。ID 主键列使用业务 ID 的 SHA-256 十六进制值；原始 ID、应用名和服务定义保存在 JSON 正文中，搜索列保留可读原值。

| 数据             | 保存位置                                                            |
|------------------|---------------------------------------------------------------------|
| 应用档案         | `meteor_applications.body` / `revision`                             |
| 实例档案         | `meteor_instances.body` / `revision`                                |
| 实例运行详情     | `meteor_instances.runtime_body` / `runtime_revision`                |
| 最近实例登记会话 | `meteor_instances.session_id`，离线后保留                           |
| 服务定义         | `meteor_services.body` / `revision`                                 |
| 实例服务详情     | `meteor_instance_services.body` / `revision`                        |
| 成员角色         | `meteor_instance_services.member_role`，取 `Provider` 或 `Consumer` |
| 协议名称         | `meteor_member_protocols.protocol_name`                             |

实例服务关系通过 `instance_id` 关联实例，通过 `service_id` 关联服务定义。关系与实例属于同一登记会话、实例运行详情非空且租约有效时，成员处于在线状态。

租约失效后，查询立即过滤实例运行信息、成员关系和实例治理数据，数据库不会自动删除行。正常关闭、恢复登记或接管时清空实例的 `runtime_body`、`runtime_revision` 和 `rule_controls`，并删除对应会话的关系、协议及临时治理行。实例档案与最近登记会话保留；长期遗留数据可按运维策略清理。

### 治理正文

`meteor_governance` 按以下范围定位记录：

| 范围        | `instance_id`  | `service_id`   |
|-------------|----------------|----------------|
| 全局        | `global`       | `global`       |
| 服务        | `global`       | 服务 ID 存储键 |
| 实例        | 实例 ID 存储键 | `global`       |
| 实例 + 服务 | 实例 ID 存储键 | 服务 ID 存储键 |

| `item_name`          | `body` 内容                                      |
|----------------------|--------------------------------------------------|
| `state`              | 状态 JSON，如 `{"enabled":false,"block":null}`   |
| `metadata`           | 元信息 JSON，如 `{"metadata":{"team":"orders"}}` |
| `rule/SERVICE_ROUTE` | 服务路由 XML                                     |
| `rule/METHOD_ROUTE`  | 方法路由 XML                                     |
| `rule/ARGS_ROUTE`    | 参数路由 XML                                     |
| `rule/FLOW_CONTROL`  | 流控规则 XML                                     |

规则行的 `body` 直接保存完整 XML：

```xml

<route>
    <return value='addresses'/>
</route>
```

`revision` 列保存记录版本；实例范围的 `session_id` 记录所属应用会话。仅设置规则开关时，规则行的 `body` 为 SQL `NULL`。

### 规则开关

各范围的开关集中在已有详情行的 `rule_controls` JSON 列：

| 范围        | 保存位置                                            |
|-------------|-----------------------------------------------------|
| 全局        | `meteor_governance` 的 `global / global / state` 行 |
| 服务        | `meteor_services` 的目标服务行                      |
| 实例        | `meteor_instances` 的目标实例行                     |
| 实例 + 服务 | `meteor_instance_services` 的目标实例服务行         |

```json
{
  "ruleEnabled": {
    "SERVICE_ROUTE": false,
    "FLOW_CONTROL": true
  },
  "ruleCleared": ["FLOW_CONTROL"]
}
```

`ruleEnabled` 按规则类型保存布尔值，禁用时保留正文。`ruleCleared` 记录显式清空的规则类型：`rule=null` 表示恢复继承，`rule=""` 表示显式清空。删除规则时清除其正文、开关和清空标记。

全局状态行可仅保存规则开关，此时 `body` 为 SQL `NULL`。全局与服务治理持久保留，实例及实例服务治理随应用登记失效。
