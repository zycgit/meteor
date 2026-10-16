/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.channel;
import java.net.URL;
import java.util.Map;
import java.util.ServiceLoader;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolFactory;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.soap.SoapGet;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoapProtocolTest {
    @Test
    public void spiDeclaresSoapOverHttp() {
        // 从正式 SPI 清单中发现 SOAP 协议，不直接创建实现类。
        ProtocolFactory<?> soap = ServiceLoader.load(ProtocolFactory.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(factory -> StringUtils.equals("soap", factory.name())) //
                .findFirst() //
                .orElse(null);

        // 协议身份固定为 SOAP over HTTP，并接收 HTTP exchange。
        assertNotNull(soap);
        assertEquals("soap", soap.name());
        assertEquals("http", soap.transportType());
        assertEquals("Xml", soap.defaultSerializeType());
        assertEquals(HttpExchange.class, soap.messageType());
    }

    @Test
    public void serviceWithoutSoapAliasIsRejected() throws Exception {
        // 准备合法 WSDL，但服务声明不包含 SOAP 别名。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        service.putAliasName("soap", "");
        ProtocolConfig config = this.configuration();

        // 直接检查协议 SPI 的发布校验，缺少别名必须立即失败。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("SOAP service must declare a soap alias", failure.getMessage());
    }

    @Test
    public void serviceWithUnsafeSoapAliasIsRejected() throws Exception {
        // 别名包含不能作为单个 URI 路径段的空白和查询分隔符。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        service.putAliasName("soap", "Echo Service?");
        ProtocolConfig config = this.configuration();

        // 错误别名不能被注册到后续路由。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("SOAP service alias must be one URI path segment: Echo Service?", failure.getMessage());
    }

    @Test
    public void externalWsdlMayUseANumericEndpointAlias() throws Exception {
        // 外部 WSDL 已自行命名组件，SOAP 别名只参与 HTTP 地址构造。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        service.putAliasName("soap", "123");
        ProtocolConfig config = this.configuration();

        // XML NCName 限制只适用于自动生成，不能缩窄合法 URI 路径别名。
        new SoapProtocol().validateService(config, service);
    }

    @Test
    public void currentDirectoryAliasIsRejected() throws Exception {
        // 外部契约不会限制 XML 组件名，但端点仍不能使用当前目录路径段。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        service.putAliasName("soap", ".");
        ProtocolConfig config = this.configuration();

        // 防止 HTTP 客户端规范化地址后改变已发布的服务端点。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("SOAP service alias must be one URI path segment: .", failure.getMessage());
    }

    @Test
    public void parentDirectoryAliasIsRejected() throws Exception {
        // 外部契约的别名同样不能退回父目录。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        service.putAliasName("soap", "..");
        ProtocolConfig config = this.configuration();

        // 发布阶段拒绝路径折叠，保留挂载点的实际隔离范围。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("SOAP service alias must be one URI path segment: ..", failure.getMessage());
    }

    @Test
    public void javaMethodMissingFromWsdlIsRejected() throws Exception {
        // WSDL 只声明 echo，Java 方法却命名为 missing。
        ServiceDomain<MismatchedService> service = this.service(MismatchedService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = this.configuration();

        // 解析成功的 WSDL 也必须与实际 Java 方法一致。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("WSDL operation not found for Java method: missing", failure.getMessage());
    }

    @Test
    public void oneWayJavaMethodMustReturnVoid() throws Exception {
        // signal 的 WSDL 只声明 input，但 Java 方法错误地声明返回值。
        ServiceDomain<ReturningSignalService> service = this.service(ReturningSignalService.class, "/wsdl/signal.wsdl");
        ProtocolConfig config = this.configuration();

        // one-way 不能暗中丢弃业务返回值。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("SOAP one-way method must return void: signal", failure.getMessage());
    }

    @Test
    public void responseResultMustMatchJavaReturnType() throws Exception {
        // WSDL 声明字符串结果，Java 方法却使用 void。
        ServiceDomain<VoidEchoService> service = this.service(VoidEchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = this.configuration();

        // 输出 wrapper 和 Java 返回类型不一致时拒绝发布。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("WSDL result does not match Java return type: echo", failure.getMessage());
    }

    @Test
    public void soap12ContractCannotBeMountedWithTheDefaultVersion() throws Exception {
        // 未配置版本时仍为 SOAP 1.1，而绑定资源使用 SOAP 1.2。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo12.wsdl");
        ProtocolConfig config = this.configuration();

        // 契约版本与实际 HTTP 消息版本不一致时，在发布前拒绝。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("WSDL SOAP version does not match soapVersion: 1.1", failure.getMessage());
    }

    @Test
    public void soap11ContractCannotBeMountedAsSoap12() throws Exception {
        // 端点明确选择 SOAP 1.2，但调用契约仍使用 SOAP 1.1。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapVersion", "1.2"));

        // 双向拒绝版本错配，避免服务描述与线上消息分裂。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("WSDL SOAP version does not match soapVersion: 1.2", failure.getMessage());
    }

    @Test
    public void soap12ContractCanBeMountedWithMatchingVersion() throws Exception {
        // 端点和外部契约都明确选择 SOAP 1.2。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo12.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapVersion", "1.2"));

        // 合法契约通过同一个发布校验入口。
        new SoapProtocol().validateService(config, service);
    }

    @Test
    public void unsupportedSoapVersionIsRejected() {
        // 自动生成契约也必须受协议配置版本约束。
        ServiceDomain<EchoService> service = new ServiceDomain<>(EchoService.class);
        service.putAliasName("soap", "EchoService");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapVersion", "2.0"));

        // 无效配置不能静默退回 SOAP 1.1。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("SOAP soapVersion must be 1.1 or 1.2: 2.0", failure.getMessage());
    }

    @Test
    public void soapGetCannotDeclareJavaParameters() {
        // GET 没有 SOAP 请求正文，不能为 Java 形参提供映射。
        ServiceDomain<ParameterizedGetService> service = new ServiceDomain<>(ParameterizedGetService.class);
        service.putAliasName("soap", "StatusService");
        ProtocolConfig config = this.configuration();

        // 即使当前挂载为 SOAP 1.1，错误的 GET 声明也必须被发现。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("SOAP GET method must not declare parameters: status", failure.getMessage());
    }

    @Test
    public void soapGetCannotUseMessageMode() {
        // GET 读取操作必须等待业务响应，不能声明 one-way 消息模式。
        ServiceDomain<MessageGetService> service = new ServiceDomain<>(MessageGetService.class);
        service.putAliasName("soap", "StatusService");
        ProtocolConfig config = this.configuration();

        // 方法注解中的模式冲突在发布准备阶段拒绝。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("SOAP GET method must not use message mode: status", failure.getMessage());
    }

    @Test
    public void unknownAttachmentModeIsRejectedDuringPublication() throws Exception {
        // 附件模式在服务发布时确定，错误配置不应拖到首个请求。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("attachmentMode", "unknown"));

        // 协议发布校验直接说明支持的模式。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("SOAP attachmentMode must be inline, mtom or swa", failure.getMessage());
    }

    @Test
    public void negativeAttachmentThresholdIsRejectedDuringPublication() throws Exception {
        // 附件提取阈值以字节计数，不能使用负数。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("attachmentThreshold", "-1"));

        // 发布准备阶段拒绝无效阈值。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("SOAP attachmentThreshold must not be negative", failure.getMessage());
    }

    @Test
    public void nonIntegerAttachmentThresholdIsRejectedDuringPublication() throws Exception {
        // 配置入口的阈值为字符串，需要在发布时验证其整数表示。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("attachmentThreshold", "1.5"));

        // 不允许以隐式截断或默认值掩盖非法配置。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().validateService(config, service));
        assertEquals("SOAP attachmentThreshold must be an integer", failure.getMessage());
    }

    @Test
    public void zeroAttachmentThresholdIsAcceptedDuringPublication() throws Exception {
        // 零阈值表示允许提取全部二进制数据，是合法边界。
        ServiceDomain<EchoService> service = this.service(EchoService.class, "/wsdl/echo.wsdl");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", //
                Map.of("attachmentMode", "mtom", "attachmentThreshold", "0"));

        // 与运行时附件提取规则保持一致，发布应成功。
        new SoapProtocol().validateService(config, service);
    }

    private ProtocolConfig configuration() {
        return ProtocolConfig.newConfig("soap", "soap", Map.of("contextPath", "/services"));
    }

    private <T> ServiceDomain<T> service(Class<T> type, String path) throws Exception {
        URL wsdl = this.getClass().getResource(path);
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("soap", "EchoService");
        service.setBindResource(new SoapWsdlResource(wsdl));
        return service;
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
}
