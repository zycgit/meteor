# 公共复杂类型向量

`values.json` 定义所有 self profile 共用的业务输入。`values.proto`、`values.thrift` 与
`values.x` 表达同一组嵌套对象、有序列表、DTO Map 和 int32 Map；IDL adapter 仅转换边界数据，
收据始终由 `ComplexValueServiceImpl` 生成。

Thrift 使用与 SDK 一致的 Apache Thrift Compiler 0.25.0 生成标准 Java 合约，输出仅位于
`meteor-test/src/test/java/net/hasor/example/contract/complex/thrift/generated`，不进入生产 JAR。
生成文件只增加项目版权头并规范化行尾空白，不手工简化 Client、Processor、TBase 或元数据，
也不在日常构建中调用编译器。在仓库根目录使用现成官方编译器重新生成：

```bash
meteor-test/src/test/thrift/prepare-compiler.sh
THRIFT_COMPILER="$PWD/build/thrift-compiler-0.25.0/bin/thrift" \
  bash meteor-test/src/test/resources/vectors/complex/regenerate-thrift.sh
```

准备脚本使用 Apache 官方 0.25.0 源包，SHA256 为
`66da4707214c54c94bac082103dc67adaf9e08925662700f269170a7b534b214`。
已有同版本编译器时可直接将 `THRIFT_COMPILER` 指向其绝对路径。再生脚本拒绝不同版本，
避免 0.19 的 Processor 泛型元数据与 0.25 SDK 不兼容。

ONC RPC 的 Map 显式映射为有界 key/value entry 数组，不依赖 Java Map 推断。
`OncComplexCodecs` 按 `values.x` 的字段顺序读取与写入，限制 ASCII 字符串和集合长度。
