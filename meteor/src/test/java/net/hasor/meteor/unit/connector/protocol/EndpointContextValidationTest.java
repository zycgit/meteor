/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.protocol;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.EndpointConnectorFactory;
import net.hasor.meteor.domain.ContextPath;
import net.hasor.meteor.domain.ServiceDomain;
import org.junit.Test;
import static org.junit.Assert.*;

public class EndpointContextValidationTest {
    @Test
    public void laterSchemeSelectionSupersedesUndeclaredNameContext() {
        // 复用已注册的 HTTP 协议 SPI，为协议名称和地址 scheme 分别设置标识。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "wire-a", "httpa", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        EndpointConnectorFactory factory = new EndpointConnectorFactory();
        factory.listenTypes(this.getClass().getClassLoader());
        factory.prepare(endpoint);

        // 名称选择的旧路径未声明，随后通过 scheme 改为已经声明的路径。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("HTTPA", new ContextPath(List.of("/missing"), false));
        service.setBindContextPaths("WIRE-A", new ContextPath(List.of("/orders"), false));

        // 校验只检查同一协议最后生效的上下文，旧路径不会阻止发布。
        factory.validateService(List.of(endpoint), service);
        assertEquals(List.of("/orders"), protocol.contextPaths(service));
    }

    @Test
    public void repeatedNameSelectionSupersedesUndeclaredSchemeContext() {
        // 协议名称和 scheme 指向同一挂载，只声明最终需要的路径。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "wire-a", "httpa", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        EndpointConnectorFactory factory = new EndpointConnectorFactory();
        factory.listenTypes(this.getClass().getClassLoader());
        factory.prepare(endpoint);

        // 再次使用原名称时，该次调用必须排在中间的 scheme 选择之后。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/old-name"), false));
        service.setBindContextPaths("wire-a", new ContextPath(List.of("/old-scheme"), false));
        service.setBindContextPaths("HTTPA", new ContextPath(List.of("/orders"), false));

        // 两个旧选择均未声明，但最终有效路径已声明，校验应成功。
        factory.validateService(List.of(endpoint), service);
        assertEquals(List.of("/orders"), protocol.contextPaths(service));
    }

    @Test
    public void undeclaredFinalAliasContextIsRejected() {
        // 协议仅声明订单路径，较早的有效选择不能掩盖最后一次无效选择。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "wire-a", "httpa", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        EndpointConnectorFactory factory = new EndpointConnectorFactory();
        factory.listenTypes(this.getClass().getClassLoader());
        factory.prepare(endpoint);

        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/orders"), false));
        service.setBindContextPaths("wire-a", new ContextPath(List.of("/missing"), false));

        // 最终路径没有声明，公开校验入口必须明确报告这个路径。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            factory.validateService(List.of(endpoint), service);
        });
        assertTrue(failure.getMessage().contains("Context is not configured"));
        assertTrue(failure.getMessage().contains("/missing"));
    }

    @Test
    public void validAliasDoesNotHideAnEarlierUnknownProtocolSelector() {
        // 只配置一个已知协议，未知选择器不能被当成这个协议的旧别名。
        ProtocolConfig protocol = new ProtocolConfig("httpa", "wire-a", "httpa", Map.of("contextPath", "/orders"));
        ConnectorConfig endpoint = this.endpoint(protocol);
        EndpointConnectorFactory factory = new EndpointConnectorFactory();
        factory.listenTypes(this.getClass().getClassLoader());
        factory.prepare(endpoint);

        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("missing-protocol", new ContextPath(List.of("/orders"), false));
        service.setBindContextPaths("wire-a", new ContextPath(List.of("/orders"), false));

        // 即使最后一个选择合法，先前引用的未知协议仍须拒绝。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            factory.validateService(List.of(endpoint), service);
        });
        assertTrue(failure.getMessage().contains("unknown protocol"));
        assertTrue(failure.getMessage().contains("missing-protocol"));
    }

    @Test
    public void contextSelectorOutsideServiceProtocolScopeIsRejected() {
        // 两个协议均已配置，服务仅绑定其中一个协议。
        ProtocolConfig first = new ProtocolConfig("httpa", "wire-a", "httpa", Map.of("contextPath", "/orders"));
        ProtocolConfig second = new ProtocolConfig("httpb", "wire-b", "httpb", Map.of("contextPath", "/inventory"));
        ConnectorConfig endpoint = this.endpoint(first, second);
        EndpointConnectorFactory factory = new EndpointConnectorFactory();
        factory.listenTypes(this.getClass().getClassLoader());
        factory.prepare(endpoint);

        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.addBindProtocol("HTTPB");
        service.setBindContextPaths("wire-a", new ContextPath(List.of("/orders"), false));

        // 路径虽然已经声明，选择上下文也不能扩大服务的协议发布范围。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            factory.validateService(List.of(endpoint), service);
        });
        assertTrue(failure.getMessage().contains("unbound protocol"));
        assertTrue(failure.getMessage().contains("wire-a"));
    }

    private ConnectorConfig endpoint(ProtocolConfig... protocols) {
        InterAddress address = InterAddress.forBinding("http", "127.0.0.1", 0, "test");
        return new ConnectorConfig("http-contexts", address, Map.of("listenType", "http"), List.of(protocols), false);
    }
}
