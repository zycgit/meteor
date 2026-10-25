# dubbo2.js 版本消费者

比较原版 `dubbo2.js` 1.0.4 与 2.3.9 的直连消费者，两版共用公开的
`DirectlyDubbo.from`、`proxyService`、`java.String` 和 `{err, res}` 返回结构。
每个版本独立安装 npm 依赖，准备阶段将入口复制到该版本的 `peer.js`。

```sh
node peer.js consumer dubbo://<host>:<port> [message]
```

服务为 `net.hasor.example.protocol.dubbo.interop.DubboInteropService`，
group 为 `interop`，服务版本为 `1.0.0`。单次 `echo` 校验 Unicode 原文，成功输出 `OK`。

公开配置 `dubboVersion` 表示写入请求正文的协议版本，与 npm 包版本分别记录。
1.0.4 使用原厂 README 和 `es7/directly-dubbo.js` 示例中的 `2.0.0`，其
`es7/decode.js` 仅支持结果标志 0、1、2；2.3.9 使用 `2.0.2`，其解码器还支持带附件的 3、4、5。
原始包可从 [npm 1.0.4](https://www.npmjs.com/package/dubbo2.js/v/1.0.4) 和
[npm 2.3.9](https://www.npmjs.com/package/dubbo2.js/v/2.3.9) 核对。
两个版本的 `DirectlyDubbo` 均要求调用方提供此配置，不会补默认值。
入口不修改 SDK、线报文或响应头，不进行重试。SDK 没有公开关闭接口，结果写完后结束独占进程。

这里只提供 SDK → Meteor 场景，未实现或声明 Node SDK 服务端能力。
