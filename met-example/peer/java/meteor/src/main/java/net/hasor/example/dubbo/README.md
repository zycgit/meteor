# Java Dubbo Demo

## 介绍

使用 Meteor 和 Apache Dubbo 双向调用订单服务。

## 特性

- SDK 使用 Apache Dubbo，服务分组为 `default`，版本为 `1.0.0`。
- SDK 注册发现使用 ZooKeeper；Meteor 两端也支持 Nacos、ZooKeeper。

## 用法

使用 JDK 17，在 IDEA 中打开 `met-example` 目录并完成 Gradle 依赖同步。Meteor 入口位于 `peer/java/meteor`，SDK 入口位于 `peer/java/apache-dubbo/3.3.6`。

业务地址固定为 `dubbo://127.0.0.1:8080`，订单 ID 为 `1001`。

提供者监听 `0.0.0.0:8080`，消费者连接 `127.0.0.1:8080`。

## Demo 如何启动

按顺序运行下列 Main 的 `main` 方法，提供者和消费者各占一个进程。每次选择一组示例，切换前停止上一组提供者。

### 直连

<a id="meteor-to-meteor"></a>

#### Meteor → Meteor

1. 运行提供者：Meteor，[ProviderMain](ProviderMain.java)。
2. 运行消费者：Meteor，[ConsumerMain](ConsumerMain.java)。

<a id="sdk-to-meteor"></a>

#### Apache Dubbo → Meteor

1. 运行提供者：Meteor，[ProviderMain](ProviderMain.java)。
2. 运行消费者：Apache Dubbo，[SdkConsumerMain](../../../../../../../../apache-dubbo/3.3.6/src/main/java/net/hasor/example/dubbo/SdkConsumerMain.java)。

<a id="meteor-to-sdk"></a>

#### Meteor → Apache Dubbo

1. 运行提供者：Apache Dubbo，[SdkProviderMain](../../../../../../../../apache-dubbo/3.3.6/src/main/java/net/hasor/example/dubbo/SdkProviderMain.java)。
2. 运行消费者：Meteor，[ConsumerMain](ConsumerMain.java)。

### 注册发现

<a id="meteor-to-meteor-registry"></a>
<a id="meteor-to-meteor-nacos"></a>

#### Meteor → Meteor（Nacos）

先启动本地 Nacos（`127.0.0.1:8848`）。

1. 运行提供者：Meteor，[NacosRegistryProviderMain](NacosRegistryProviderMain.java)。

2. 运行消费者：Meteor，[NacosRegistryConsumerMain](NacosRegistryConsumerMain.java)。

<a id="meteor-to-meteor-zookeeper"></a>

#### Meteor → Meteor（ZooKeeper）

先启动本地 ZooKeeper（`127.0.0.1:2181`）。

1. 运行提供者：Meteor，[ZookeeperRegistryProviderMain](ZookeeperRegistryProviderMain.java)。

2. 运行消费者：Meteor，[ZookeeperRegistryConsumerMain](ZookeeperRegistryConsumerMain.java)。

以下两组使用 Apache Dubbo 的注册格式，通过 `DubboRegistryConfiguration` 接入；上方 Meteor 两端示例使用 Meteor 的注册格式。先启动本地 ZooKeeper（`127.0.0.1:2181`）。

<a id="sdk-registry-to-meteor"></a>

#### Apache Dubbo → Meteor（ZooKeeper）

1. 运行提供者：Meteor，[NativeRegistryProviderMain](NativeRegistryProviderMain.java)。
2. 运行消费者：Apache Dubbo SDK，[SdkRegistryConsumerMain](../../../../../../../../apache-dubbo/3.3.6/src/main/java/net/hasor/example/dubbo/SdkRegistryConsumerMain.java)。

<a id="meteor-registry-to-sdk"></a>

#### Meteor → Apache Dubbo（ZooKeeper）

1. 运行提供者：Apache Dubbo SDK，[SdkRegistryProviderMain](../../../../../../../../apache-dubbo/3.3.6/src/main/java/net/hasor/example/dubbo/SdkRegistryProviderMain.java)。
2. 运行消费者：Meteor，[NativeRegistryConsumerMain](NativeRegistryConsumerMain.java)。
