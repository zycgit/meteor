# Meteor 开发规范

适用于本仓库所有模块，包括生产代码、测试代码和示例。

## Java 类型

1. 未经用户明确指示，不得使用 Java `record`。新增类型使用普通类或 POJO，不得擅自将已有类改写为 `record`。

## 接口设计

1. 接口方法默认声明为抽象方法，不提供 `default` 实现。仅在用户明确要求，或已有兼容性契约明确需要默认实现时使用 `default` 方法。
2. 用户明确授权的局部例外：`transport.http.HttpProtocolProvider`、`transport.tcp.TcpProtocolProvider`、`transport.udp.UdpProtocolProvider` 的 `transportType()`、`messageType()` 使用 `default` 返回所属传输与固定消息类型，具体协议入口继承这些实现。公共 `ProtocolProvider` 的方法仍为抽象声明，三个传输接口的其他方法继续遵守抽象方法规则。

## 构造方法

1. 类默认只声明一个构造方法，包含不同可见性的构造方法在内。不为补齐默认参数、便捷调用或测试辅助增加构造方法重载。
2. 不同构建语义使用具名静态工厂方法表达，并共用唯一构造方法。只允许通过工厂方法创建的类，将构造方法设为 `private`。

## 集合顺序

1. 固定列表的元素顺序用于编号映射、位置对应、处理优先级或执行先后时，使用逐项 `add` 构建，并在构建处说明具体顺序要求。保留原有只读性和空值约束；已逐项构建的列表在必要处补充顺序说明。

## 对象所有权与生命周期

1. 创建并持续持有对象的一方负责该对象的生命周期编排，包括初始化、启动、关闭和释放；所有者通过子对象公开的生命周期入口进行管理，子对象只释放自己当前拥有的内部资源。
2. 对象可以关闭自行创建且未转出的资源，以及 API 明确转入所有权的资源；不得反向关闭其创建者，也不得关闭按借用方式传入、由上下文提供或由多个对象共享的资源。
3. 所有权发生转移时，API 和实现必须清楚表达新的管理者；工厂返回的独占新对象默认将所有权转给调用方，无法确认所有权的外部依赖按借用对象处理。

## 工具类

1. 工具能力优先使用 Cobble 提供的实现。需要工具方法时，先查找 Cobble 中是否已有语义匹配的能力，再考虑 JDK、已有第三方依赖或自行实现。
2. 字符串相等比较统一使用 `net.hasor.cobble.StringUtils.equals`；忽略大小写时使用 `StringUtils.equalsIgnoreCase`，保留业务原有的大小写语义。
3. 不直接使用字符串的 `.equals()`、`.equalsIgnoreCase()` 或 `::equals`，也不使用 `java.util.Objects.equals` 代替字符串工具。常量与变量、两个变量的比较均遵守此要求。
4. 字符串与 `Object` 类型的值（例如元信息值、反射调用参数）比较时，使用 Cobble 的 `ObjectUtils.equals` 保留对象比较语义，不通过 `toString()`、`String.valueOf()` 或不安全强转来迁就字符串工具。
5. 集合、枚举和业务对象的比较应遵循各自语义，不机械替换为字符串比较。
6. 通过 `import` 引入工具类，代码正文使用简单类名，不写类的全限定名。

```java
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;

boolean rsf = StringUtils.equals("rsf", protocol);
boolean http = StringUtils.equalsIgnoreCase("http", protocol);
boolean managed = ObjectUtils.equals("managed", metadata.get("owner"));
```

## JSON

1. 内核以 `api` 声明 Jackson JSON/XML 依赖，不内嵌或重定位；扩展通过依赖内核获得原始 `com.fasterxml.jackson.*` 类型，不重复声明相同依赖。
2. 各模块可以直接使用 Jackson，也可以按自己的职责封装；不强制统一 JSON 工具或读写器接口。模块自行持有和配置 `ObjectMapper`、`JsonMapper` 或 `XmlMapper`，保留协议所需的重复字段、空值、数值精度和兼容性规则，不在发布后修改共享 mapper 的配置。
3. 借入的输入、输出流由调用方关闭，使用 Jackson 时按实际流调用配置自动关闭行为。官方互通对端保持独立依赖和类路径，使用对端自身的 JSON 能力。
4. 用户的 JSON 序列化通过 `JsonSerializeDelegate` 替换，接口只依赖 JDK 类型；委托只影响所属编码器，不改变框架内部 JSON 规则。

## 依赖与构建

1. `build.gradle` 按插件、模块说明、配置、依赖、任务组织，依赖区分 API、实现、运行及测试。Cobble、DataQL、dbVisitor、Hasor、Neta 的依赖版本、跨模块共用的 Jackson、OGNL 版本及通用构建、测试工具版本由根 `gradle.properties` 管理；其他第三方依赖版本由所属模块的 `build.gradle` 管理。使用方通过项目或制品的传递依赖获得已有 API，不读取其他模块的版本配置；独立 SDK 对端自行管理其兼容版本，共用 Jackson 版本不合并其类路径或强制覆盖传递依赖。独立构建中仅有一处声明的依赖仍由该模块管理。工具链、测试、覆盖率和库发布的公共配置仍放在根脚本，避免自定义预处理、二次打包和多层任务封装。
2. 内核及 integration 库的生产依赖默认声明为 `api`，向用户同时传递编译和运行依赖，用户无需重复声明。仅在运行期通过 SPI、反射等加载的实现依赖可使用 `runtimeOnly`；Console、示例等应用模块按实际用途使用 `implementation` 或 `runtimeOnly`。依赖的传递范围不决定 Java API 的可见性，内部协作类型和方法仍保持最小必要可见性。
3. Java 测试依赖统一使用 `testImplementation`，不再细分 `testRuntimeOnly`。官方对端保留独立 JVM 和运行依赖隔离，优先复用标准测试编译输出和已解析依赖；仅在版本冲突等明确需要时保留独立配置或 SourceSet。
4. 生产依赖使用普通版本声明或常规 `platform`，不通过 `force`、`strictly` 或 `enforcedPlatform` 强制消费方版本；允许应用显式声明兼容的更高版本，或通过 BOM 统一相关模块版本。
5. 根构建延续 dbVisitor 的组织方式：公共 Java、测试、覆盖率和标准发布声明由根 `build.gradle` 管理；构建、安装与发布流程由 `build.sh` 编排。根 `build.sh` 只处理产品模块，不加载、清理或构建 `meteor-test` 及其配套工程，也不编排 NXN；交叉测试通过 `meteor-test/runnxn.sh` 或显式 Gradle 测试任务独立运行。模块专属工具、JVM 开放参数与子进程环境由所属模块管理。
6. OGNL 沿用 dbVisitor 的分阶段方式：源码编译使用 `compileOnly`，需要执行规则的源码测试和应用显式声明测试或运行依赖，不改写项目运行变体。Maven 发布时内嵌并重定位到 `net.hasor.meteor.utils.ognl`，其 Javassist 依赖归该命名空间下，不向库消费者传递独立依赖。

## 公共注解与描述

1. 公共注解位于 `net.hasor.meteor.annotation`；公共服务、方法、参数、DTO、属性及枚举描述位于 `net.hasor.meteor.domain.meta`，由 `ServiceMetaParser` 统一读取、合并和解析泛型。
   注解内部统一使用 `id`，元信息传递按所属领域限定编号名称：数值契约服务号使用 `contractServiceId`，方法编号使用 `methodId`，参数、属性及枚举编号使用 `fieldId`。对应公共元信息、描述 DTO、注册存储与 JSON 使用相同的限定名称。注册中心仅在实例服务的完整 `description` 中保存数值契约编号，服务目录及摘要使用字符串 `serviceId` 表示登记身份，Java 接口名只保存于完整描述的 `javaType`。成员角色归属运行登记，完整描述不重复保存 `role`；端点统一以 `protocolName` 表示协议名，`scheme` 表示地址前缀。名称继续使用 `name`，协议端点的 `name` 表示当前绑定的有效服务名。`MetService.version` 保持字符串服务版本语义，ONC RPC 程序版本归协议资源或契约文件管理。
2. 协议扩展通过公共描述取得公共注解信息，不重复实现公共注解解析。协议专属注解、文件语义、编号范围、线上定义和编解码绑定由各协议维护。
3. 公共 Java 描述只保存声明，不持有业务实例、端点选项或契约资源；`ProtocolMeta` 单独管理协议资源及端点发布结果。Jackson/XML 的有效属性与 mixin 按当前 mapper 建立视图，不能写入按类复用的公共声明。
4. `ProtocolConfig` 是 `domain` 下的协议配置模型。协议提供者通过 `prepareBinding(config, context, meta)` 校验服务并准备当前端点的协议定义及纯数据描述，使用 `meta.build(config, definition, description)` 一次创建完整端点绑定。原始路径声明由 `ProtocolMeta` 保存，默认路径取自当前端点配置，有效路径与 `contextExact` 在构建时确定。每个协议在一轮发布中只创建一份候选元信息，各端点绑定按连接器名称直接收集到本轮候选元信息的 `endpoints`，全部准备成功后统一提交同一对象；失败时保留旧发布状态。
5. 元信息与描述 DTO 的字段依次按编号、名称及 Java 定位、主体内容、扩展状态与缓存分组，扩展 Map 放在最后。保持声明名称与有效名称的既有含义，字段重排不改变泛型解析、端点隔离或发布提交语义。

## SOAP 映射

1. 公共 `MetType`、`MetField`、`MetEnumValue` 只补充类型、属性和枚举名称；`MetMethod`、`MetParam`、`MetReturn` 命名操作和输入输出。名称使用 `name`，编号使用 `id`，不增加 SOAP 专属的名称、顺序、必填或可空成员。
2. XML 命名空间、attribute、text、集合包装、忽略、访问权限和构造方式使用 Jackson XML / Jackson。公共名称覆盖同一成员的 Jackson localName，其他 XML 与 Java 绑定设置继续生效。集合使用 `JacksonXmlElementWrapper`；单值、属性分组和 Map 外层容器使用普通嵌套 DTO。
3. XSD 与 WSDL 在发布时根据接口、DTO、声明泛型和当前挂载的 mapper 确定。精确 QName、part、Header、Fault、choice、出现次数、固定值等由 WSDL/XSD 资源表达；命名资源只属于指定协议。已有文件决定线上契约，明确的 Java 名称或类型冲突在发布时报告。
4. 服务准备结果持有已编译的 XML 类型图、Java 方法和参数对应表。运行期复用准备结果，服务撤销后在途请求继续持有自己的绑定。编解码不创建业务服务，静态准备不创建 DTO 实例，也不根据某次业务返回值扩充 Schema。
5. literal 沿用 Jackson 的构造与自定义读写能力；同一配置派生 XML 形状映射与展开后的 Java token 绑定，XML 包装只处理一次。自定义读写器使用标准 Jackson token 接口；encoded 保留对象图能力，并在发布时拒绝其不能表示的映射。
6. 动态 XML Map 使用 String key，XML 属性 Map 的值使用简单内容；namespace、nil、empty、缺失值和默认值分别按当前协议绑定处理。开放 Object 沿用声明类型、Jackson 定制及标准 xsi:type，不能从文本猜测任意业务类。

## Connector 与 Transport 命名

1. `net.hasor.meteor.connector` 以类名前缀区分稳定职责，前缀表达所属模块，不表达临时实现步骤：
   - `Plan*` 负责启动期收集、校验和固化连接器与协议挂载计划。`ConnectorManager` 初始化时依次编译全部端点计划、准备全部协议挂载、创建端点；`ConnectorEndpoint` 从 Manager 获取已准备的动态查询函数，根据 `PlanEndpoint` 创建并持有 `RouteTable`。业务服务发布后，通过同步事件完成协议校验和服务路径登记。
   - `Route*` 负责运行期路由。`RouteDefinition` 是已编译定义，`RouteProbeResult` 是探测结果，
     `RouteSelection` 是选择结果，`RouteChannelDispatcher` 在物理连接上向协议通道分派消息。
   - `Protocol*` 表示协议扩展契约、配置和会话。`ProtocolMountBinding` 是端点挂载结果，
     `ProtocolSessionContext` 是会话创建上下文，`ProtocolNetworkChannel` 是协议限定的网络通道。
   - `Connector*` 表示连接器编排以及协议通道的对外适配。
2. `net.hasor.meteor.connector.transport` 及其子包遵循以下前缀：
   - `Transport*` 表示传输配置、启动计划、运行期依赖和扩展 SPI。
   - `Network*` 表示连接、监听和通道等运行期网络契约。
   - `Channel*` 表示通道事件、监听器和生命周期。
   - `Route*` 表示传输消息的路由选择、路由结果和路由处理器。
   - `Socket*` 表示基于 Neta Socket 的公共实现。
   - `Tcp*`、`Udp*`、`Http*`、`WebSocket*`、`Ssl*`、`Dtls*` 只用于对应传输或安全实现。
3. 具体类型按“能力或协议 + 版本 + 角色 + 职责”命名，例如 `Http1ClientChannel`；没有版本差异时不添加版本号。
4. 后缀必须表达稳定语义：`Factory` 创建同类对象或计划，`Provider` 提供一个领域的扩展能力，`Connector` 管理一种传输端点，`Channel` 表示可收发通道，
   `Handler` 处理事件或协议回调，`Pipeline` 组装处理链，`Utils` 仅用于无状态工具方法。
5. 主要职责是实现 `ProtoHandler` 的类型必须以 `Handler` 结尾；工具类必须以 `Utils` 结尾。
6. 不使用 `Support`、`Common`、`Helper` 等宽泛名称掩盖职责，也不为统一前缀机械拆分或合并类型。
   能由已有所有者清楚承担的逻辑优先合并；具有独立状态、并发边界或资源所有权的类型必须保留。
7. 名称必须表达处理阶段和方向。例如 `Http1ClientResponseHandler` 只处理客户端响应，
   `Http1ClientExchangeState` 保存客户端交换状态；`ChannelListenerRelay` 中继监听器创建前到达的通道事件，
   `ChannelLifecycleHandler` 则是 Neta 管线中的生命周期处理器。不使用 `Handler`、`Call`、`Context` 等宽泛后缀省略真实职责。
8. RSF/1 线协议的帧、编解码、属性池和缓冲区类型统一使用 `RsfV1*` 前缀。不将帧编解码器命名为
   `Protocol`，也不使用 `Block`、`Adapter` 掩盖帧、编解码器或属性池的具体职责。
9. HTTP 传输根包只负责 SPI 与端点组装；`http.channel` 保存共享通道模型，`http.http1`、`http.http2`、
   `http.websocket` 保存各自实现。功能子包只能依赖 `http.channel`，不得反向依赖根组装包或互相依赖。

10. 协议插件按以下职责组织：

    - Provider 是组装入口，位于各自协议根包，并以该入口类型注册到公共 `ProtocolProvider` SPI。
    - 公共 `ProtocolProvider<M>` 提供协议元信息、`prepareBinding` 服务绑定准备和 `createSession` 会话创建；自定义传输直接使用该接口。
    - HTTP、TCP、UDP 插件分别实现 `transport.http.HttpProtocolProvider`、`transport.tcp.TcpProtocolProvider`、`transport.udp.UdpProtocolProvider`。三个接口继承固定消息类型的公共接口，按“接口设计”中的例外提供默认方法，具体入口不重复声明。
    - TCP/UDP 各自声明 `probeProtocol`，HTTP 按挂载路径选路。识别逻辑由协议插件提供，传输层执行路由。多协议字节监听端点按传输类型检查探测接口，缺少能力时启动失败；单协议和纯出站端点不强制探测接口，出站协议由连接请求指定。
    - 协议定义通过 `describeContract` 导出契约；`ProtocolSession` 负责连接上的握手、收发和关闭；`codec`、`session` 或 `channel` 保存具体实现。

## 构建与测试产物

项目构建、测试和维护脚本生成的临时文件、日志、控制状态及运行缓存默认放在本仓库的 `build/` 或 `.gradle/` 中；子进程继承相应的临时目录和用户缓存目录。不得默认写入系统临时目录、用户 HOME 或 Windows TEMP。Gradle 共享缓存及用户配置遵循 Gradle 标准位置和 `GRADLE_USER_HOME`，不由 Wrapper 重写。用户显式设置的缓存位置、安装及发布目标按其配置处理；已有依赖和凭据可作为只读输入。Docker 镜像与受管理卷由 Docker 维护，运行诊断归档回仓库。清理历史文件前核实归属和进程占用，不删除其他项目的数据或共享缓存。

`meteor-test` 专用的编译结果、运行归档、日志、控制状态、校时诊断及维护工具产物统一放在 `meteor-test/` 内的 `build/` 目录，报告路径相对于 `meteor-test/`。

## AI 辅助资料与技能

1. [AI 辅助开发索引](document/docs/other/ai/README.md)说明仓库技能、开发约定和维护备注的存放方式。
2. 编写、补齐、审查或整理 Java 测试时，读取并使用 [meteor-test-style](.agents/skills/meteor-test-style/SKILL.md)。按任务需要读取其参考材料，不将测试整理扩展为无关生产代码改造。
3. 编写或重写 Git 提交说明时，读取并使用 [git-commit-intent](.agents/skills/git-commit-intent/SKILL.md)，根据对应 diff 描述功能、真实意图与兼容影响，避免文件清单式日志。
4. 审查或重构 Java 包结构、类型命名、依赖方向及可见性时，读取并使用 [$meteor-package-naming-review](.agents/skills/meteor-package-naming-review/SKILL.md)。先还原真实调用链、对象所有权和生命周期，再确定目标包树与重命名映射；不得仅按类名机械迁包。

## 测试与示例归属

1. 单元测试与被测生产类同包，放在 `src/test/java`，不另设 `unit` 包。场景归 `net.hasor.realmeteor`，业务示例归 `net.hasor.example`，再按模块与功能分包，例如 `net.hasor.meteor.connector`、`net.hasor.realmeteor.protocol.grpc`、`net.hasor.example.protocol.grpc`。夹具就近归属，迁包后删除多余访问适配器，不为测试修改生产 API 或可见性。
2. 单元测试与必要的基础功能测试归属被测实现所在模块。`meteor-integration` 测试仅使用 Java；每个协议提供者都须有 Meteor 两端通过真实网络调用的基础场景，不能用本地调用替代。
3. Meteor 自体互通、官方 SDK、多语言、注册发现及多协议组合测试统一归 `meteor-test`，按 `self`、`provider`、`consumer` 记录矩阵。协议插件不承载官方 SDK 对端、跨语言或跨协议组合测试；同一场景在测试工程内只保留一份。
4. 外部 Java SDK 互通优先使用官方 Java/JVM SDK，其次成熟 Java 实现；无法覆盖的协议与 SDK 限制必须记录，不用其他语言代替 Java 覆盖。
5. 官方 SDK 互通使用公开 API 和官方生成契约，保留原厂源码与发布制品内容。允许公开配置、官方支持或经 SDK 自体验证可用的依赖组合，并记录运行图；兼容修复由 Meteor 承担，不通过对端改包、改写报文、monkey patch 或隐藏重试掩盖问题。不可用组合保留失败证据并说明限制。
6. `met-example` 仅向开发者展示用法，语言入口归 `peer/<语言>`，Java 按真实 SDK 与必要版本分目录；资源、证书与工具独立持有。`meteor-test` 独立持有测试模型、契约、对端入口和 SDK 依赖。两者互不依赖，不通过源码目录、生成脚本、资源路径或 Docker 构建间接复用；允许相同模型和契约分别存在，不以去重破坏边界。

## meteor-test 布局与执行

以下路径均相对于 `meteor-test/`：

- `peer/<语言>`：全部语言业务代码及其工具链 Dockerfile；正常启动脚本随所属 peer。
- `peer/java/meteor/src/main/java`、`peer/java/meteor/src/test/java`：Java 应用与 JUnit 场景。
- `peer/java/<SDK 名称>`：按真实实现划分 SDK 对端；API/ABI 不兼容版本归显式版本子目录，兼容版本共用源码并隔离运行依赖。Java 根构建聚合 SDK 编译与运行清单，不设笼统的 `sdk` 目录。
- `peer/java/contracts`：Java 公共接口、DTO 与生成绑定。各编译域自行声明依赖，SDK 保留独立 JVM 和 classpath，不将冲突依赖合入 Meteor 测试运行图。
- `src/main/java/net/hasor/meteor/test/nxn`：Java 编排框架；`src/main/c`：Linux 原生进程执行器。外层 `src` 只保留 NXN 框架、框架配置及其单测。
- `resource/protocol/{proto,thrift,x,wsdl,xsd}`：语言无关的共享契约；`resource/data`：共享数据；`resource/certs`：测试证书。Java 专属 fixture、SPI 和断言数据归 Java 模块的标准资源目录。
- `tools`：契约生成、手工诊断和可选校时维护工具。

1. 宿主通过 `nxn.gradle` 中的 `:meteor-test:dockerTest` 统一编排，仅需 JDK 17、Docker 与 Compose。完整测试在容器内构建，不依赖宿主 Java 编译、SDK 运行图解析或 Python；容器内工具链及 `peer/python` 对端可以使用 Python。
2. `docker/compose.yml` 每轮统一创建语言容器及注册中心。默认 16 个并发场景共用容器，每个场景启动独立程序，不按 testcase 或分组复制容器栈。
3. Compose 管理容器生命周期；场景只在已有容器内执行程序，管理自身进程、端口、临时目录、用户缓存与注册数据，不关闭共享容器。需要独立节点或本机网络语义的场景显式使用专用节点。
