# Java ONC RPC Demo

## 介绍

演示 Meteor 与 RemoteTea Java SDK 双向调用 ONC RPC 订单服务。

## 特性

- 使用 TCP/XDR，契约为 [order.x](../../../../../../../../../resource/order.x)，无需 rpcbind。
- 程序号为 `0x31234569`，版本为 `1`；`save`、`find` 的过程号为 `1`、`2`。

## 用法

使用 JDK 17，在 IDEA 中打开 `met-example` 目录并完成 Gradle 依赖同步。Meteor 入口位于 `java/meteor`，SDK 入口位于 `java/sdk/remotetea`。

本地业务地址为 `127.0.0.1:8080`。Meteor 使用 [OncRpcOrderService](OncRpcOrderService.java)，RemoteTea 使用 [SdkOrderValue](../../../../../../../../sdk/remotetea/src/main/java/net/hasor/example/oncrpc/SdkOrderValue.java) 映射订单。

## Demo 如何启动

按顺序运行下列 Main 的 `main` 方法，提供者和消费者各占一个进程。每次选择一组示例，切换前停止上一组提供者。

### 直连

<a id="meteor-to-meteor"></a>

#### Meteor → Meteor

1. 运行提供者：Meteor [ProviderMain](ProviderMain.java)。

2. 运行消费者：Meteor [ConsumerMain](ConsumerMain.java)。

<a id="sdk-to-meteor"></a>

#### RemoteTea → Meteor

1. 运行提供者：Meteor [ProviderMain](ProviderMain.java)。

2. 运行消费者：RemoteTea [SdkConsumerMain](../../../../../../../../sdk/remotetea/src/main/java/net/hasor/example/oncrpc/SdkConsumerMain.java)。

<a id="meteor-to-sdk"></a>

#### Meteor → RemoteTea

1. 运行提供者：RemoteTea [SdkProviderMain](../../../../../../../../sdk/remotetea/src/main/java/net/hasor/example/oncrpc/SdkProviderMain.java)。

2. 运行消费者：Meteor [ConsumerMain](ConsumerMain.java)。

### 注册发现

<a id="meteor-to-meteor-registry"></a>
<a id="meteor-to-meteor-nacos"></a>

#### Meteor → Meteor（Nacos）

先启动本地 Nacos `127.0.0.1:8848`。

1. 运行提供者：Meteor [NacosRegistryProviderMain](NacosRegistryProviderMain.java)。

2. 运行消费者：Meteor [NacosRegistryConsumerMain](NacosRegistryConsumerMain.java)。

<a id="meteor-to-meteor-zookeeper"></a>

#### Meteor → Meteor（ZooKeeper）

先启动本地 ZooKeeper `127.0.0.1:2181`。

1. 运行提供者：Meteor [ZookeeperRegistryProviderMain](ZookeeperRegistryProviderMain.java)。

2. 运行消费者：Meteor [ZookeeperRegistryConsumerMain](ZookeeperRegistryConsumerMain.java)。
