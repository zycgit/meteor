/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.Flow;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ContextPath;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.soap.annotation.SoapGet;
import net.hasor.meteor.protocol.soap.definition.SoapProtocolMeta;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SoapProtocolTest {
    @Test
    public void distinctWireNamesDoNotMakeJavaOverloadsPublishable() {
        // 当前 Java 调用绑定按方法名定位，不把两个重载静默覆盖成同一份接入信息。
        ServiceDomain<AliasedOverloads> service = new ServiceDomain<>(AliasedOverloads.class);
        service.putAliasName("soap", "Overloads");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void spiDeclaresSoapOverHttp() {
        // 从正式 SPI 清单中发现 SOAP 协议，不直接创建实现类。
        ProtocolProvider<?> soap = ServiceLoader.load(ProtocolProvider.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(provider -> StringUtils.equals("soap", provider.name())) //
                .findFirst() //
                .orElse(null);

        // 协议身份固定为 SOAP over HTTP，并接收 HTTP exchange。
        assertNotNull(soap);
        assertEquals("soap", soap.name());
        assertEquals("http", soap.transportType());
        assertEquals("Xml", soap.defaultSerialize());
        assertEquals(HttpExchange.class, soap.messageType());
    }

    @Test
    public void serviceWithoutSoapAliasUsesItsDeclaredName() throws Exception {
        // 外部 WSDL 决定 XML 契约，服务登记名只决定运行时端点。
        ServiceDomain<EchoService> service = new ServiceDomain<>(EchoService.class);
        service.setBindName("LocalEcho");
        service.setBindResource("soap", new SoapWsdlResource(this.getClass().getResource("/wsdl/echo.wsdl")));
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 默认端点名不能重命名 WSDL 的 service、port 或命名空间。
        ProtocolBinding binding = new SoapProtocol().prepareBinding(config, null, meta);
        SoapProtocolMeta prepared = (SoapProtocolMeta) binding.getDefinition();
        URI endpoint = URI.create("http://localhost/services/LocalEcho");
        String wsdl = new String(prepared.resource().renderWsdl(endpoint), StandardCharsets.UTF_8);
        assertEquals("LocalEcho", binding.getDescription().path("name").asText());
        assertTrue(wsdl.contains("name=\"EchoService\""));
        assertTrue(wsdl.contains("name=\"EchoPort\""));
        assertTrue(wsdl.contains("targetNamespace=\"urn:meteor:test:echo\""));
        assertTrue(wsdl.contains("location=\"" + endpoint + "\""));
    }

    @Test
    public void explicitEmptySoapAliasIsRejected() throws Exception {
        // 已显式配置的空别名属于错误配置，不能回退到服务名掩盖它。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        service.putAliasName("soap", "");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP service name must be one URI path segment: ", failure.getMessage());
    }

    @Test
    public void unsafeDefaultServiceNameIsRejected() throws Exception {
        // 没有协议别名时，默认服务名也必须通过端点路径校验。
        ServiceDomain<EchoService> service = new ServiceDomain<>(EchoService.class);
        service.setBindName("unsafe/echo");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP service name must be one URI path segment: unsafe/echo", failure.getMessage());
    }

    @Test
    public void explicitSoapAliasOverridesTheDefaultServiceName() throws Exception {
        // 内部服务名不适合 XML/HTTP 时，调用方可以显式给出合法协议名。
        ServiceDomain<EchoService> service = new ServiceDomain<>(EchoService.class);
        service.setBindName("internal/echo");
        service.putAliasName("soap", "PublicEcho");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 发布描述和生成契约使用同一个生效名称。
        ProtocolBinding binding = new SoapProtocol().prepareBinding(config, null, meta);
        SoapProtocolMeta prepared = (SoapProtocolMeta) binding.getDefinition();
        String wsdl = new String(prepared.resource().renderWsdl( //
                URI.create("http://localhost/services/PublicEcho")), StandardCharsets.UTF_8);
        assertEquals("PublicEcho", binding.getDescription().path("name").asText());
        assertTrue(wsdl.contains("name=\"PublicEcho\""));
        assertTrue(wsdl.contains("targetNamespace=\"urn:hasor:meteor:soap:PublicEcho\""));
    }

    @Test
    public void generatedDefaultServiceNameMustBeAnXmlLocalName() {
        // 生成 WSDL 时默认名同样用于 service、portType 和 binding 组件。
        ServiceDomain<EchoService> service = new ServiceDomain<>(EchoService.class);
        service.setBindName("123");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 纯数字可作为外部 WSDL 的端点路径，但不能生成 XML NCName。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP service name must be an XML local name: 123", failure.getMessage());
    }

    @Test
    public void rejectedRenameDoesNotChangeThePreparedServiceName() throws Exception {
        // 已接受的端点必须保存发布时名称，不能在路由时重新读取可变服务配置。
        ServiceDomain<EchoService> service = new ServiceDomain<>(EchoService.class);
        service.setBindName("AcceptedEcho");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        SoapProtocol protocol = new SoapProtocol();
        ProtocolBinding accepted = protocol.prepareBinding(config, null, meta);
        SoapProtocolMeta prepared = (SoapProtocolMeta) accepted.getDefinition();

        // 后续无效重命名失败时，原协议定义及路由键保持不变。
        service.putAliasName("soap", "invalid/name");
        assertThrows(IllegalArgumentException.class, () -> protocol.prepareBinding(config, null, meta));
        assertEquals("AcceptedEcho", prepared.serviceName());
        assertEquals(List.of("AcceptedEcho"), protocol.serviceKey(service, accepted));
        String wsdl = new String(prepared.resource().renderWsdl( //
                URI.create("http://localhost/services/AcceptedEcho")), StandardCharsets.UTF_8);
        assertTrue(wsdl.contains("name=\"AcceptedEcho\""));
    }

    @Test
    public void serviceWithUnsafeSoapAliasIsRejected() throws Exception {
        // 别名包含不能作为单个 URI 路径段的空白和查询分隔符。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        service.putAliasName("soap", "Echo Service?");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 错误别名不能被注册到后续路由。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP service name must be one URI path segment: Echo Service?", failure.getMessage());
    }

    @Test
    public void externalWsdlMayUseANumericEndpointAlias() throws Exception {
        // 外部 WSDL 已自行命名组件，SOAP 别名只参与 HTTP 地址构造。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        service.putAliasName("soap", "123");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // XML NCName 限制只适用于自动生成，不能缩窄合法 URI 路径别名。
        assertNotNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void currentDirectoryAliasIsRejected() throws Exception {
        // 外部契约不会限制 XML 组件名，但端点仍不能使用当前目录路径段。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        service.putAliasName("soap", ".");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 防止 HTTP 客户端规范化地址后改变已发布的服务端点。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP service name must be one URI path segment: .", failure.getMessage());
    }

    @Test
    public void parentDirectoryAliasIsRejected() throws Exception {
        // 外部契约的别名同样不能退回父目录。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        service.putAliasName("soap", "..");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 发布阶段拒绝路径折叠，保留挂载点的实际隔离范围。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP service name must be one URI path segment: ..", failure.getMessage());
    }

    @Test
    public void javaMethodMissingFromWsdlIsRejected() throws Exception {
        // WSDL 只声明 echo，Java 方法却命名为 missing。
        ServiceDomain<MismatchedService> service = this.service(MismatchedService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 解析成功的 WSDL 也必须与实际 Java 方法一致。
        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void oneWayJavaMethodMustReturnVoid() throws Exception {
        // signal 的 WSDL 只声明 input，但 Java 方法错误地声明返回值。
        ServiceDomain<ReturningSignalService> service = this.service(ReturningSignalService.class, "/wsdl/signal.wsdl");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // one-way 不能暗中丢弃业务返回值。
        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void responseResultMustMatchJavaReturnType() throws Exception {
        // WSDL 声明字符串结果，Java 方法却使用 void。
        ServiceDomain<VoidEchoService> service = this.service(VoidEchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 输出 wrapper 和 Java 返回类型不一致时拒绝发布。
        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void soap12ContractCannotBeMountedWithTheDefaultVersion() throws Exception {
        // 未配置版本时仍为 SOAP 1.1，而绑定资源使用 SOAP 1.2。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo12.wsdl");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 契约版本与实际 HTTP 消息版本不一致时，在发布前拒绝。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("WSDL SOAP version does not match soapVersion: 1.1", failure.getMessage());
    }

    @Test
    public void soap11ContractCannotBeMountedAsSoap12() throws Exception {
        // 端点明确选择 SOAP 1.2，但调用契约仍使用 SOAP 1.1。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapVersion", "1.2"));
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 双向拒绝版本错配，避免服务描述与线上消息分裂。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("WSDL SOAP version does not match soapVersion: 1.2", failure.getMessage());
    }

    @Test
    public void soap12ContractCanBeMountedWithMatchingVersion() throws Exception {
        // 端点和外部契约都明确选择 SOAP 1.2。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo12.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapVersion", "1.2"));
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 合法契约通过同一个发布校验入口。
        new SoapProtocol().prepareBinding(config, null, meta);
    }

    @Test
    public void unsupportedSoapVersionIsRejected() {
        // 自动生成契约也必须受协议配置版本约束。
        ServiceDomain<EchoService> service = new ServiceDomain<>(EchoService.class);
        service.putAliasName("soap", "EchoService");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapVersion", "2.0"));
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 无效配置不能静默退回 SOAP 1.1。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP soapVersion must be 1.1 or 1.2: 2.0", failure.getMessage());
    }

    @Test
    public void soapGetCannotDeclareJavaParameters() {
        // GET 没有 SOAP 请求正文，不能为 Java 形参提供映射。
        ServiceDomain<ParameterizedGetService> service = new ServiceDomain<>(ParameterizedGetService.class);
        service.putAliasName("soap", "StatusService");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 即使当前挂载为 SOAP 1.1，错误的 GET 声明也必须被发现。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP GET method must not declare parameters: status", failure.getMessage());
    }

    @Test
    public void soapGetCannotUseMessageMode() {
        // GET 读取操作必须等待业务响应，不能声明 one-way 消息模式。
        ServiceDomain<MessageGetService> service = new ServiceDomain<>(MessageGetService.class);
        service.putAliasName("soap", "StatusService");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 方法注解中的模式冲突在发布准备阶段拒绝。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP GET method must not use message mode: status", failure.getMessage());
    }

    @Test
    public void unknownAttachmentModeIsRejectedDuringPublication() throws Exception {
        // 附件模式在服务发布时确定，错误配置不应拖到首个请求。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("attachmentMode", "unknown"));
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 协议发布校验直接说明支持的模式。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP attachmentMode must be inline, mtom or swa", failure.getMessage());
    }

    @Test
    public void negativeAttachmentThresholdIsRejectedDuringPublication() throws Exception {
        // 附件提取阈值以字节计数，不能使用负数。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("attachmentThreshold", "-1"));
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 发布准备阶段拒绝无效阈值。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP attachmentThreshold must not be negative", failure.getMessage());
    }

    @Test
    public void nonIntegerAttachmentThresholdIsRejectedDuringPublication() throws Exception {
        // 配置入口的阈值为字符串，需要在发布时验证其整数表示。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("attachmentThreshold", "1.5"));
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 不允许以隐式截断或默认值掩盖非法配置。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("SOAP attachmentThreshold must be an integer", failure.getMessage());
    }

    @Test
    public void zeroAttachmentThresholdIsAcceptedDuringPublication() throws Exception {
        // 零阈值表示允许提取全部二进制数据，是合法边界。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", //
                Map.of("attachmentMode", "mtom", "attachmentThreshold", "0"));
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 与运行时附件提取规则保持一致，发布应成功。
        new SoapProtocol().prepareBinding(config, null, meta);
    }

    @Test
    public void publisherSignatureIsNotAcceptedBySoap() {
        // 流式接口可以由其它协议处理，SOAP 不为其生成不完整契约。
        ServiceDomain<StreamingService> service = new ServiceDomain<>(StreamingService.class);
        service.putAliasName("soap", "StreamingService");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 不支持的 Java 声明只拒绝当前协议。
        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void generatedContractIsReturnedWithoutMutatingTheService() {
        // 普通 Java 接口未绑定外部 WSDL，由协议准备返回定义。
        ServiceDomain<EchoService> service = new ServiceDomain<>(EchoService.class);
        service.putAliasName("soap", "EchoService");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 调用方决定是否发布结果，准备阶段不修改服务或协议入口缓存。
        ProtocolBinding binding = new SoapProtocol().prepareBinding(config, null, meta);
        SoapProtocolMeta prepared = (SoapProtocolMeta) binding.getDefinition();
        assertNotNull(prepared.resource());
        assertNotNull(prepared.mapper());
        assertEquals("1.1", binding.getDescription().path("version").asText());
        assertFalse(binding.getDescription().path("methods").isEmpty());
        assertFalse(binding.getDescription().path("schemas").isEmpty());
        assertNull(service.getProtocolMeta("soap"));
    }

    @Test
    public void endpointBindingsKeepSoapVersionsIndependent() throws Exception {
        // 同一声明分别准备 SOAP 1.1 和 1.2，每个端点返回自己的契约与映射器。
        ServiceDomain<EchoService> service = new ServiceDomain<>(EchoService.class);
        service.putAliasName("soap", "EchoService");
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, new LinkedHashMap<>());
        ProtocolConfig first = this.configuration();
        ProtocolConfig second = ProtocolConfig.newConfig("soap", "soap", //
                Map.of("soapVersion", "1.2", "contextPath", "/secondary"));
        SoapProtocol protocol = new SoapProtocol();
        ProtocolBinding firstBinding = protocol.prepareBinding(first, null, declaration);
        ProtocolBinding secondBinding = protocol.prepareBinding(second, null, declaration);
        assertEquals(new ContextPath(List.of("/services"), false), firstBinding.getContextPath());
        assertEquals(new ContextPath(List.of("/secondary"), false), secondBinding.getContextPath());
        assertNull(declaration.getContextPath());
        assertEquals("1.1", firstBinding.getDescription().path("version").asText());
        assertEquals("1.2", secondBinding.getDescription().path("version").asText());
        assertNotSame(firstBinding.getDescription(), secondBinding.getDescription());

        // 全部准备成功后补全同一份元信息，具体 SOAP 定义仍由各自端点持有。
        declaration.getEndpoints().put("soap11", firstBinding);
        declaration.getEndpoints().put("soap12", secondBinding);
        SoapProtocolMeta soap11Definition = (SoapProtocolMeta) declaration.getEndpoint("soap11").getDefinition();
        SoapProtocolMeta soap12Definition = (SoapProtocolMeta) declaration.getEndpoint("soap12").getDefinition();
        assertSame(firstBinding, declaration.getEndpoint("soap11"));
        assertSame(secondBinding, declaration.getEndpoint("soap12"));
        assertNotSame(soap11Definition.resource(), soap12Definition.resource());
        assertNotSame(soap11Definition.mapper(), soap12Definition.mapper());

        // 两份 WSDL 的绑定版本各自保持正确，准备结果不会写入服务对象。
        URI endpoint = URI.create("http://localhost/services/EchoService");
        String soap11 = new String(soap11Definition.resource().renderWsdl(endpoint), StandardCharsets.UTF_8);
        String soap12 = new String(soap12Definition.resource().renderWsdl(endpoint), StandardCharsets.UTF_8);
        assertTrue(soap11.contains("http://schemas.xmlsoap.org/wsdl/soap/"));
        assertTrue(soap12.contains("http://schemas.xmlsoap.org/wsdl/soap12/"));
        assertNull(service.getProtocolMeta("soap"));
    }

    @Test
    public void failedEndpointPreparationDoesNotChangeSuccessfulBinding() throws Exception {
        // 先为一个端点准备合法契约，再用同一声明准备损坏的端点配置。
        ServiceDomain<EchoService> service = new ServiceDomain<>(EchoService.class);
        service.putAliasName("soap", "EchoService");
        ProtocolMeta declaration = new ProtocolMeta(service, null, null, Map.of());
        ProtocolConfig first = this.configuration();
        SoapProtocol protocol = new SoapProtocol();
        ProtocolBinding binding = protocol.prepareBinding(first, null, declaration);
        SoapProtocolMeta definition = (SoapProtocolMeta) binding.getDefinition();
        String acceptedDescription = binding.getDescription().toString();
        ProtocolConfig invalid = ProtocolConfig.newConfig("soap", "soap", Map.of("soapVersion", "2.0"));

        // 失败不会修改已有端点定义，也不会把未接受的结果加入声明。
        assertThrows(IllegalArgumentException.class, () -> protocol.prepareBinding(invalid, null, declaration));
        assertSame(definition, binding.getDefinition());
        assertEquals(acceptedDescription, binding.getDescription().toString());
        assertTrue(declaration.getEndpoints().isEmpty());
        URI endpoint = URI.create("http://localhost/services/EchoService");
        String wsdl = new String(definition.resource().renderWsdl(endpoint), StandardCharsets.UTF_8);
        assertTrue(wsdl.contains("http://schemas.xmlsoap.org/wsdl/soap/"));
        assertNull(service.getProtocolMeta("soap"));
    }

    @Test
    public void multipartContractWithoutMatchingResultPropertiesIsNotAccepted() throws Exception {
        // WSDL 定义两个输出 part，而 Java 返回值不包含对应属性。
        ServiceDomain<ScalarMultipartService> service = this.service( //
                ScalarMultipartService.class, "/wsdl/styles/document-multipart.wsdl");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 有效契约无法映射该签名时拒绝端点，不把它报告为损坏的 WSDL。
        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void multipartContractWithNonStringResultMapKeysIsNotAccepted() throws Exception {
        // WSDL 的 part 名是字符串，Integer 键的返回 Map 无法承载它们。
        ServiceDomain<IntegerMapMultipartService> service = this.service( //
                IntegerMapMultipartService.class, "/wsdl/styles/document-multipart.wsdl");
        ProtocolConfig config = this.configuration();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 只拒绝当前协议，不隐藏其它注解冲突或非法配置异常。
        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void generatedEncodedContractRejectsMapParametersBeforeGeneratingSchema() {
        // Java Map 是合法声明，但 encoded 不支持动态 XML key。
        ServiceDomain<MapParameterService> service = new ServiceDomain<>(MapParameterService.class);
        service.putAliasName("soap", "MapParameterService");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapUse", "encoded"));
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 只拒绝 SOAP 端点，调用方仍可把同一声明交给其它协议或本地容器。
        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
        assertNull(service.getProtocolMeta("soap"));
    }

    @Test
    public void generatedEncodedContractRejectsMapResultsBeforeGeneratingSchema() {
        // 无参数的方法仍需在生成前验证返回值能否被 encoded 承载。
        ServiceDomain<MapResultService> service = new ServiceDomain<>(MapResultService.class);
        service.putAliasName("soap", "MapResultService");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapUse", "encoded"));
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 不把合法 Java Map 误报为损坏的生成契约。
        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void generatedEncodedContractRejectsCollectionsWithoutAnElementType() {
        // 原始 List 缺少 encoded 所需的具体元素声明。
        ServiceDomain<RawCollectionService> service = new ServiceDomain<>(RawCollectionService.class);
        service.putAliasName("soap", "RawCollectionService");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapUse", "encoded"));
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 无法准备此协议定义时返回拒绝结果，不抛全局发布异常。
        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void generatedEncodedContractRejectsDtoWithoutANoArgumentConstructor() {
        // DTO 可由其它序列化器承载，但 encoded 的对象图绑定需要无参构造。
        ServiceDomain<ConstructorValueService> service = new ServiceDomain<>(ConstructorValueService.class);
        service.putAliasName("soap", "ConstructorValueService");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapUse", "encoded"));
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 构造能力不匹配属于端点不支持该声明。
        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void unsupportedGeneratedTypeDoesNotHideAnInvalidBindingOption() {
        // Map 不受 encoded 支持，同时配置包含非法 style；先报告真实配置错误。
        ServiceDomain<MapParameterService> service = new ServiceDomain<>(MapParameterService.class);
        service.putAliasName("soap", "MapParameterService");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", //
                Map.of("soapUse", "encoded", "soapStyle", "invalid"));
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 类型预检不能将无效配置降格为静默拒绝。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertEquals("Unsupported SOAP soapStyle: invalid", failure.getMessage());
    }

    private ProtocolConfig configuration() {
        return ProtocolConfig.newConfig("soap", "soap", Map.of("contextPath", "/services"));
    }

    private <T> ServiceDomain<T> service(Class<T> type, String path) throws Exception {
        URL wsdl = this.getClass().getResource(path);
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("soap", "EchoService");
        service.setBindResource("soap", new SoapWsdlResource(wsdl));
        return service;
    }

    private interface MapParameterService {
        String echo(Map<String, String> values);
    }

    private interface MapResultService {
        Map<String, String> values();
    }

    private interface RawCollectionService {
        String echo(List values);
    }

    private interface ConstructorValueService {
        String echo(ConstructorValue value);
    }

    public static final class ConstructorValue {
        public String value;

        public ConstructorValue(String value) {
            this.value = value;
        }
    }

    private interface ScalarMultipartService {
        String combine(String first, String last);
    }

    private interface IntegerMapMultipartService {
        Map<Integer, String> combine(String first, String last);
    }

    private interface StreamingService {
        Flow.Publisher<String> echo(Flow.Publisher<String> values);
    }

    private interface ParameterizedGetService {
        @SoapGet
        String status(String value);
    }

    private interface MessageGetService {
        @SoapGet
        @MetMessage
        void status();
    }

    private interface MismatchedService {
        String missing(String value);
    }

    private interface ReturningSignalService {
        String signal(String value);
    }

    private interface VoidEchoService {
        void echo(String value);
    }

    public interface AliasedOverloads {
        @MetMethod(name = "EchoText")
        String echo(String value);

        @MetMethod(name = "EchoNumber")
        int echo(int value);
    }

}
