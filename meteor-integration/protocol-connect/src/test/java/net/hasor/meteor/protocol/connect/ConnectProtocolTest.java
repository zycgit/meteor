/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect;
import java.util.Map;
import java.util.ServiceLoader;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectMessage;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.transport.http.HttpProtocolProvider;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.binding.GrpcServiceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

public class ConnectProtocolTest {
    @Test
    public void spiDiscoversTheConnectHttpContract() {
        // 使用生产 SPI 资源发现协议，核对正式组装入口与默认编码。
        ProtocolProvider<?> provider = ServiceLoader.load(ProtocolProvider.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(value -> StringUtils.equals("connect", value.name())) //
                .findFirst() //
                .orElse(null);

        // 传输与消息契约由插件声明，应用只选择已注册的名称。
        assertNotNull(provider);
        assertEquals(ConnectProtocol.class, provider.getClass());
        assertTrue(provider instanceof HttpProtocolProvider);
        assertEquals("http", provider.transportType());
        assertEquals("Protobuf", provider.defaultSerialize());
        assertEquals(HttpExchange.class, provider.messageType());
    }

    @Test
    public void supportedJsonServiceCreatesAnEndpointBindingWithoutProto() {
        // 普通 JSON 服务不绑定 proto，也无需创建连接或运行期会话。
        Map<String, String> options = Map.of("compression", "gzip");
        ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", options);
        ServiceDomain<ConnectDemoService> service = jsonService();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 接受结果保存当前端点准备的 Java 方法映射。
        ProtocolBinding binding = new ConnectProtocol().prepareBinding(config, null, meta);
        assertNotNull(binding);
        assertNotNull(binding.getDefinition());
        assertEquals("Json", binding.getDescription().path("serializeType").asText());
        assertEquals(9, binding.getDescription().path("methods").size());
    }

    @Test
    public void eachEndpointPreparesItsOwnBindingAndDefinition() {
        // 相同协议的两个端点各自提供地址前缀和运行参数。
        ProtocolConfig first = ProtocolConfig.newConfig("connect", "connect", Map.of("compression", "identity"));
        ProtocolConfig second = ProtocolConfig.newConfig("connect", "connects", Map.of("compression", "gzip"));
        ProtocolMeta meta = new ProtocolMeta(jsonService(), null, null, Map.of());
        ConnectProtocol provider = new ConnectProtocol();

        // 两次准备分别生成绑定和 Java 方法映射，不修改另一端点的结果。
        ProtocolBinding original = provider.prepareBinding(first, null, meta);
        ProtocolBinding alternate = provider.prepareBinding(second, null, meta);
        assertNotSame(original, alternate);
        assertNotNull(original.getDefinition());
        assertNotNull(alternate.getDefinition());
        assertNotSame(original.getDefinition(), alternate.getDefinition());
        assertNotSame(original.getDescription(), alternate.getDescription());
    }

    @Test
    public void protobufServiceWithoutDefinitionIsNotAccepted() {
        // 缺少 proto 的服务不符合当前协议，不影响其他协议继续发布。
        ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", Map.of());
        ServiceDomain<ConnectDemoService> service = jsonService();
        service.setSerializeType("Protobuf");
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 返回 null 让组装层从该端点排除服务。
        assertNull(new ConnectProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void invalidMountConfigurationRemainsAnError() {
        // CORS 凭证要求明确来源；不支持服务和损坏配置是不同结果。
        Map<String, String> options = Map.of("corsCredentials", "true", "corsOrigins", "*");
        ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", options);
        ServiceDomain<ConnectDemoService> service = jsonService();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 错误不能被协议筛选逻辑吞掉。
        assertThrows(IllegalArgumentException.class, //
                () -> new ConnectProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void aDeclaredServiceNameBecomesTheDefaultProtocolName() {
        // 没有协议别名时使用服务自身的登记名称。
        ServiceDomain<ConnectDemoService> service = new ServiceDomain<>(ConnectDemoService.class);
        service.setBindName("orders.OrderService");
        service.setSerializeType("Json");
        ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", Map.of());
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 编码绑定和导出描述必须记录相同的有效名称。
        ProtocolBinding binding = new ConnectProtocol().prepareBinding(config, null, meta);
        GrpcServiceBinding definition = (GrpcServiceBinding) binding.getDefinition();
        assertEquals("orders.OrderService", definition.serviceName());
        assertEquals("orders.OrderService", binding.getDescription().path("name").asText());
    }

    @Test
    public void aNestedInterfaceUsesItsCanonicalName() {
        // 默认登记名来自 Java 二进制名，线上名称使用合法的点分规范名。
        ServiceDomain<NestedService> service = new ServiceDomain<>(NestedService.class);
        service.setBindName(NestedService.class.getName());
        service.setSerializeType("Json");
        ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", Map.of());
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        ProtocolBinding binding = new ConnectProtocol().prepareBinding(config, null, meta);
        assertEquals(NestedService.class.getCanonicalName(), binding.getDescription().path("name").asText());
    }

    @Test
    public void anExplicitAliasOverridesTheDefaultServiceName() {
        // 登记名称不是线上 IDL 名称时，可通过显式别名与外部契约对齐。
        ServiceDomain<ConnectDemoService> service = jsonService();
        service.setBindName("local.Service");
        ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", Map.of());
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        ProtocolBinding binding = new ConnectProtocol().prepareBinding(config, null, meta);
        assertEquals("meteor.connect.demo.ConnectDemoService", binding.getDescription().path("name").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "bad/name", "bad name", "bad.Service$Nested", ".bad", "bad..Service" })
    public void anInvalidDefaultNameIsRejectedDuringPublication(String name) {
        // 默认服务名仍需满足 IDL 的点分标识符要求。
        ServiceDomain<ConnectDemoService> service = new ServiceDomain<>(ConnectDemoService.class);
        service.setBindName(name);
        service.setSerializeType("Json");
        ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", Map.of());
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        assertThrows(IllegalArgumentException.class, //
                () -> new ConnectProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void anInvalidExplicitAliasDoesNotFallBackToTheServiceName() {
        // 显式空别名是错误配置，不能悄悄改用默认名称。
        ServiceDomain<ConnectDemoService> service = jsonService();
        service.setBindName("orders.OrderService");
        service.putAliasName("connect", "");
        ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", Map.of());
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        assertThrows(IllegalArgumentException.class, //
                () -> new ConnectProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void theDefaultNameMustIdentifyTheBoundProtoService() throws Exception {
        // 绑定资源之后不能把未在 proto 中声明的服务名发布成一个新契约。
        ServiceDomain<ConnectDemoService> service = new ServiceDomain<>(ConnectDemoService.class);
        service.setBindName("orders.OrderService");
        service.setSerializeType("Protobuf");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of());
        ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", Map.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new ConnectProtocol().prepareBinding(config, null, meta));
        assertTrue(failure.getMessage().contains("orders.OrderService"));
    }

    private static ServiceDomain<ConnectDemoService> jsonService() {
        ServiceDomain<ConnectDemoService> service = new ServiceDomain<>(ConnectDemoService.class);
        service.putAliasName("connect", "meteor.connect.demo.ConnectDemoService");
        service.setSerializeType("Json");
        return service;
    }

    public interface NestedService {
        ConnectMessage echo(ConnectMessage message);
    }
}
