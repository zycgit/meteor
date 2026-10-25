# Java SOAP Demo

## 介绍

使用 Meteor 和 Metro JAX-WS 双向调用 SOAP 订单服务。

## 特性

- 通过 HTTP 发布 SOAP 服务，SDK 使用 Metro JAX-WS。
- Meteor 自动生成 WSDL：`http://127.0.0.1:8080/soap/orders?wsdl`。

## 用法

使用 JDK 17，在 IDEA 中打开 `met-example` 目录并完成 Gradle 依赖同步。Meteor 入口位于 `java/meteor`，SDK 入口位于 `java/sdk/metro-jaxws`。

业务地址固定为 `http://127.0.0.1:8080/soap/orders`，订单 ID 为 `1001`。

## Demo 如何启动

按顺序运行下列 Main 的 `main` 方法，提供者和消费者各占一个进程。每次选择一组示例，切换前停止上一组提供者。

### 直连

<a id="meteor-to-meteor"></a>

#### Meteor → Meteor

1. 运行提供者：Meteor，[ProviderMain](ProviderMain.java)。
2. 运行消费者：Meteor，[ConsumerMain](ConsumerMain.java)。

<a id="sdk-to-meteor"></a>

#### Metro JAX-WS → Meteor

1. 运行提供者：Meteor，[ProviderMain](ProviderMain.java)。
2. 运行消费者：Metro JAX-WS，[SdkConsumerMain](../../../../../../../../sdk/metro-jaxws/src/main/java/net/hasor/example/soap/SdkConsumerMain.java)。

<a id="meteor-to-sdk"></a>

#### Meteor → Metro JAX-WS

1. 运行提供者：Metro JAX-WS，[SdkProviderMain](../../../../../../../../sdk/metro-jaxws/src/main/java/net/hasor/example/soap/SdkProviderMain.java)。
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
