# rsf-serialize

独立的序列化与反序列化模块，提供 Java、Json、Hessian、Hprose 四种编码器以及按名称注册编码器的工厂，不依赖 Hasor 容器或 RPC 网络层。

## 使用

```java
import net.hasor.rsf.serialize.SerializeCoder;
import net.hasor.rsf.serialize.SerializeFactory;

SerializeFactory factory = SerializeFactory.createFactory();
SerializeCoder coder = factory.getSerializeCoder("Json");
byte[] bytes = coder.encode("你好，Meteor");
String value = (String) coder.decode(bytes, String.class);
```

内置名称为 `Java`、`Json`、`Hessian`、`Hprose`，区分大小写，查询未注册名称返回 `null`。调用方选择编码器，模块不指定默认传输格式。编码器也可直接实例化使用。

`new SerializeFactory(classLoader)` 创建空工厂；`registerSerializeCoder(name, coder)` 初始化后注册，自定义实现需要支持并发调用。重复注册替换旧实现，初始化失败则保留旧实现。

`SerializeFactory.createFactory(Map<String, String>, ClassLoader)` 接收“名称 → 实现类名”，只注册给出的配置。实现类需有可访问的无参构造方法。

`decode(null, type)` 返回 `null`，`type` 必须非空。Java 编码要求对象可序列化，解码恢复流中记录的类型；其他编码器按指定类型读取。不同格式的数据模型并不完全相同，例如 JSON 默认省略 Map 的 null 值条目。

## 构建与运行

在工程根目录运行：

```bash
./gradlew :rsf-serialize:build
```

主 JAR、源码和 Javadoc JAR 位于 `rsf-serialize/build/libs/`；测试后自动生成 `rsf-serialize/build/reports/jacoco/test/html/index.html`。主 JAR 不内嵌依赖，使用 Gradle/Maven 依赖解析，或将依赖 JAR 一并放入运行类路径。

项目使用 JDK 17 构建，编译目标为 Java 8。**使用 Hprose 的 JDK 17 应用需要加入以下 JVM 参数**，模块测试也使用相同参数：

```text
--add-opens=java.base/java.io=ALL-UNNAMED
```

原因是沿用的 Hprose 2.0.38 通过反射访问 `ObjectStreamClass.newInstance`，开启断言时普通对象解码就会触发初始化失败。该参数是实际运行条件，不能只在测试环境配置。Java、Json、Hessian 无此 Hprose 参数要求。

迁移边界、旧调用链和验证范围见 [技术设计](../document/technical-design/RSF_SERIALIZE.md)。
