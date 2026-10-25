# Java Triple Demo

## 介绍

演示 Meteor 与 Apache Dubbo SDK 通过 Triple 双向调用订单服务。

## 特性

- 使用 HTTP/2 和 Protobuf，契约为 [order.proto](../../../../../../../../../resource/order.proto)。
- Meteor 支持直连和注册发现。

## 用法

使用 JDK 17，在 IDEA 中打开 `met-example` 目录并完成 Gradle 依赖同步。Meteor 入口位于 `java/meteor`，SDK 入口位于 `java/sdk/apache-dubbo-3.2`。

本地端口为 `8080`，挂载根路径 `/`，服务名为 `meteor.example.OrderDemoService`。SDK 使用对应的[契约接口](../../../../../../../../sdk/apache-dubbo-3.2/src/main/java/meteor/example/OrderDemoService.java)。

## Demo 如何启动

在 IDEA 中运行下列入口的 `main` 方法。提供者和消费者各占一个进程，切换前停止上一组提供者。

### 直连

<a id="meteor-to-meteor"></a>

#### Meteor → Meteor

1. 运行提供者：Meteor [ProviderMain](ProviderMain.java)。

2. 运行消费者：Meteor [ConsumerMain](ConsumerMain.java)。

<a id="sdk-to-meteor"></a>

#### Apache Dubbo → Meteor

1. 运行提供者：Meteor [ProviderMain](ProviderMain.java)。

2. 运行消费者：Apache Dubbo [SdkConsumerMain](../../../../../../../../sdk/apache-dubbo-3.2/src/main/java/net/hasor/example/triple/SdkConsumerMain.java)。

<a id="meteor-to-sdk"></a>

#### Meteor → Apache Dubbo

1. 运行提供者：Apache Dubbo [SdkProviderMain](../../../../../../../../sdk/apache-dubbo-3.2/src/main/java/net/hasor/example/triple/SdkProviderMain.java)。

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
