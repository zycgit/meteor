/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolTestAccess;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.domain.ContextPath;
import net.hasor.meteor.domain.ServiceDomain;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolSessionContextSelectionTest {
    @Test
    public void logicalNameSelectionTakesPriorityOverSchemeAlias() {
        // 声明名字和 scheme 不同的协议，并准备多个挂载目录。
        String declaredPaths = "[\"/first\",\"/latest\",\"/other\"]";
        Map<String, String> options = Map.of("contextPath", "/", "contextPaths", declaredPaths);
        ProtocolConfig protocol = ProtocolTestAccess.config("grpc", "grpcwire", "grpc", options);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        // 先按名字选择，再按大小写不同的 scheme 声明另一组目录。
        service.setBindContextPaths("GRPC", new ContextPath(List.of("/first"), false));
        service.setBindContextPaths("GRPCWIRE", new ContextPath(List.of("/latest", "/other"), false));

        // 逻辑协议名优先于公开地址别名，声明顺序不能改变选择对象。
        assertEquals(List.of("/first"), protocol.contextPaths(service));
        assertEquals("/first", protocol.contextPath(service));
        RouteMount previous = protocol.mountIndex().get("/first");
        RouteMount latest = protocol.mountIndex().get("/latest");
        assertTrue(protocol.accepts(service, previous));
        assertFalse(protocol.accepts(service, latest));
    }

    @Test
    public void laterProtocolNameSelectionReplacesSchemeSelection() {
        // 准备两个可选目录，协议名与 scheme 使用不同标识。
        Map<String, String> options = Map.of("contextPath", "/", "contextPaths", "[\"/first\",\"/latest\"]");
        ProtocolConfig protocol = ProtocolTestAccess.config("grpc", "grpcwire", "grpc", options);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        // 按 scheme 选择后再次按 SPI 名字选择。
        service.setBindContextPaths("grpcwire", new ContextPath(List.of("/first"), false));
        service.setBindContextPaths("grpc", new ContextPath(List.of("/latest"), false));

        // 后设置的协议名选择替换先前的 scheme 选择。
        assertEquals(List.of("/latest"), protocol.contextPaths(service));
    }

    @Test
    public void repeatedProtocolNameSelectionAfterSchemeBecomesEffective() {
        // 准备三个可选目录，用于交替设置协议名与 scheme。
        String declaredPaths = "[\"/first\",\"/second\",\"/latest\"]";
        Map<String, String> options = Map.of("contextPath", "/", "contextPaths", declaredPaths);
        ProtocolConfig protocol = ProtocolTestAccess.config("grpc", "grpcwire", "grpc", options);
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        // 同一个协议名再次调用后，应移到配置序列末尾。
        service.setBindContextPaths("grpc", new ContextPath(List.of("/first"), false));
        service.setBindContextPaths("grpcwire", new ContextPath(List.of("/second"), false));
        service.setBindContextPaths("GRPC", new ContextPath(List.of("/latest"), false));

        // 中间的 scheme 选择不能覆盖最后一次协议名选择。
        assertEquals(List.of("/latest"), protocol.contextPaths(service));
    }
}
