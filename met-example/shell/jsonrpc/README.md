# Shell / curl JSON-RPC 示例

## 介绍

使用 curl 调用 Meteor 发布的订单服务。

## 特性

保存并查询订单 `1001`，附件以 Base64 传递。

## 用法

需要 Bash 和 curl 7.76 以上版本。RPC 地址固定为 `http://127.0.0.1:8080/jsonrpc`。

## Demo 如何启动

### 直连

<a id="sdk-to-meteor"></a>

#### curl → Meteor

- SDK：使用 curl 发送 JSON-RPC 请求。
- 提供者：[Meteor ProviderMain](../../java/meteor/src/main/java/net/hasor/example/jsonrpc/ProviderMain.java)。
- 消费者：[consumer.sh](consumer.sh)。

先在 IDEA 运行上述提供者的 `main`。

在仓库根目录运行消费者：

```bash
bash met-example/shell/jsonrpc/consumer.sh
```

脚本输出保存、查询两次响应后退出。提供者按 Ctrl+C 停止。

返回[示例总目录](../../README.md)。
