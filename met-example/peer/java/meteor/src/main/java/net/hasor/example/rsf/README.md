# Java RSF Demo

## 介绍

使用 Meteor 发布和调用 RSF 订单服务。

## 特性

- TCP 二进制通信，直接使用普通 Java 接口。

## 用法

使用 JDK 17，在 IDEA 中打开 `met-example` 目录并完成 Gradle 依赖同步。入口位于 `peer/java/meteor` 模块。

业务地址固定为 `rsf://127.0.0.1:8080`，订单 ID 为 `1001`。

## Demo 如何启动

按顺序运行下列 Main 的 `main` 方法，提供者和消费者各占一个进程。每次选择一组示例，切换前停止上一组提供者。

### 直连

<a id="meteor-to-meteor"></a>

#### Meteor → Meteor

1. 运行提供者：Meteor，[ProviderMain](ProviderMain.java)。
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
