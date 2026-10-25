# 公共常见类型向量

这些 self 场景全部通过公开 bootstrap、发布、订阅和真实网络地址调用。
`values.json` 只是共享输入数据；线上参数始终为声明类型的 DTO，不将整个对象包成 JSON 字符串。
`ScalarValueServiceImpl` 读取字段、创建响应并生成收据，所有协议复用同一业务实现。

`ScalarValueMatrixTest` 的每个协议和输入向量组合都是独立 JUnit 实例，覆盖 bool 两值、
int64 上下界及超过 double 精度的正负整数、Float/Double 的最小正数、最大值、最小负值、
负零、两个业务枚举值、null note 与显式空 note。整数直接比较 long；浮点按原始位比较。

四个编码会规范化负零：JSON-RPC 的精确 BigDecimal 节点，以及 Hessian2、Dubbo Hessian2、
Triple Hessian2 的紧凑零编码。它们由 `ScalarZeroNormalizationTest` 独立验证正零结果，
不会报告为保留负零。其他 profile 仍按位断言负零。

`ScalarMomentMatrixTest` 使用真实 `java.util.Date`，按 UTC Instant 与 epoch 毫秒断言。
JSON/SOAP 与强 IDL 的日期映射为 epoch 毫秒，Hessian 使用原生 date，XML-RPC 使用 UTC 秒级日期文本。
Hprose 日期使用默认 JVM 时区的本地时间字段；本套 self 检查两端在同一个 JVM，
不据此宣称不同默认时区的外部 peer 可以保持同一绝对时刻。

`ScalarDecimalMatrixTest` 使用真实 `BigDecimal`，检查所有数字和 scale。
JSON 编码保留精确数字，SOAP 使用 xs:decimal，Hprose/Hessian 使用原生小数映射；
Protobuf、Thrift 与 XDR 明确使用小数字段的十进制文本，并在协议边界恢复 BigDecimal。
这个文本字段只表示一个小数，不封装业务 DTO。向量包含超出 double 精度的正负数和极小小数。

XML-RPC 原生整数只有 signed int32，日期只有秒精度，且没有任意精度小数线类型。
因此四份 int64 极值/精度向量、两份毫秒向量和三份 BigDecimal 向量不属于此原生 profile 的正向组合。
未为它们添加字符串转换。XML-RPC 的 nullable note 显式开启 `allowNil=true` 扩展。

`ScalarMissingMemberTest` 额外使用标准 JSON-RPC HTTP 报文真正省略 note 或 primitive flag，
直接捕获共享业务入口的 null/false 默认值，区别于仅从 Java null 发起普通代理调用。
Protobuf 的 note、enum 和浮点字段明确 optional；空 note、首个 enum 值和负零都有声明 presence。
Thrift 的 note 使用 optional，XDR 使用显式 presence 布尔值。

强 IDL 由 `values.proto`、`values.thrift`、`values.x` 声明。Thrift 使用与 SDK 一致的
Apache Thrift Compiler 0.25.0；已签入的五份 Java 文件只添加项目版权头并清理行尾空白，
不改写官方 Client、Processor、TBase 或元数据。重新生成：

```bash
meteor-test/scripts/thrift/prepare-compiler.sh
THRIFT_COMPILER="$PWD/meteor-test/build/thrift-compiler-0.25.0/bin/thrift" \
  bash meteor-test/src/test/resources/vectors/scalar/regenerate-thrift.sh
```

工具源包 SHA256 为 `66da4707214c54c94bac082103dc67adaf9e08925662700f269170a7b534b214`。
XDR 的 float/double/hyper 是原生类型；日期是有符号 epoch 毫秒，小数是有界 ASCII 十进制文本。
Thrift 不含 float 类型，adapter 将 Java float 精确提升到 double，并拒绝无法无损缩回的返回值。

本批矩阵只覆盖 direct，发现方式组合仍由既有基础与复杂类型矩阵单独提供证据。
未覆盖非有限浮点、所有时间类型、所有 Java 日期 API 或所有可能的缺失字段组合。
