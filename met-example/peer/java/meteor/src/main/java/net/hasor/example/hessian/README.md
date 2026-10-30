# Java Hessian Demo

## 介绍

演示 Meteor 与 Caucho Hessian 双向调用订单服务。

## 特性

- Caucho Hessian SDK 直接使用普通 Java 接口和 DTO。
- Meteor 支持直连和注册发现。

## 用法

使用 JDK 17，在 IDEA 中打开 `met-example` 目录并完成 Gradle 依赖同步。Meteor 入口位于 `peer/java/meteor`，SDK 入口位于 `peer/java/caucho-hessian`。

业务地址为 `http://127.0.0.1:8080/hessian`。共用[订单接口](../../../../../../../../contracts/src/main/java/net/hasor/example/OrderDemoService.java)和[订单模型](../../../../../../../../contracts/src/main/java/net/hasor/example/Order.java)。

## Demo 如何启动

按顺序运行下列 Main 的 `main` 方法，提供者和消费者各占一个进程。每次选择一组示例，切换前停止上一组提供者。

### 直连

<a id="meteor-to-meteor"></a>

#### Meteor → Meteor

1. 运行提供者：Meteor [ProviderMain](ProviderMain.java)。

2. 运行消费者：Meteor [ConsumerMain](ConsumerMain.java)。

<a id="sdk-to-meteor"></a>

#### Caucho Hessian → Meteor

1. 运行提供者：Meteor [ProviderMain](ProviderMain.java)。

2. 运行消费者：Caucho Hessian [SdkConsumerMain](../../../../../../../../caucho-hessian/src/main/java/net/hasor/example/hessian/SdkConsumerMain.java)。

<a id="meteor-to-sdk"></a>

#### Meteor → Caucho Hessian

1. 运行提供者：Caucho Hessian [SdkProviderMain](../../../../../../../../caucho-hessian/src/main/java/net/hasor/example/hessian/SdkProviderMain.java)。

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
