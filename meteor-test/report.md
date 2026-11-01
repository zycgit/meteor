# Meteor RPC 测试报告

结果：通过。

skipError：`true`。

RPC：`dubbo`，协议版本：`2`。

用例筛选：`*SdkConsumerScenarioTest.*`。

通过 2 项，失败 0 项，跳过 0 项，已确认未执行 0 项。

详细结果：[summary.json](build/docker-runs/meteor-test/build/interoperability/summary.json)

## SDK 版本兼容

— 表示本轮未执行该方向，不表示 SDK 不支持。

| 语言 | 协议 | SDK | 版本 | 运行图 | SDK → Meteor | Meteor → SDK |
| --- | --- | --- | --- | --- | --- | --- |
| java | dubbo | Alibaba Dubbo | 2.6.12 | dubboClassic | 通过 | — |
| java | dubbo | Apache Dubbo | 2.7.23 | dubboLegacy | 通过 | — |

完整日志与 XML：[本轮归档](build/docker-runs)
