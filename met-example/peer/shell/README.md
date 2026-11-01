# Shell / curl 示例

[返回示例目录](../../README.md)

## 运行准备

准备 Bash 和 curl 7.76 或更新版本，按[统一启动约定](../../README.md#run)准备 Java 端。以下命令从 Meteor 仓库根目录执行。

<a id="jsonrpc"></a>

## JSON-RPC

通过 curl 调用 `http://127.0.0.1:8080/jsonrpc`，保存并查询订单 `1001`，附件以 Base64 传递。

### curl → Meteor（直连）

先在 IDEA 运行 Meteor [ProviderMain](../java/meteor/src/main/java/net/hasor/example/jsonrpc/ProviderMain.java)，再执行消费者 [consumer.sh](jsonrpc/consumer.sh)：

```bash
bash met-example/peer/shell/jsonrpc/consumer.sh
```

脚本输出保存、查询两次响应后退出。
