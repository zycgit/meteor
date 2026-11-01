/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.meta;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.domain.ContextPath;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ProtocolMetaTest {
    @Test
    public void endpointDefaultsProduceIndependentBindingsWithoutChangingTheDeclaration() {
        // 同一协议元信息供两个端点使用，各端点拥有自己的默认目录和定义。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ProtocolConfig first = ProtocolConfig.newConfig("http", "http", Map.of("contextPath", "/orders/"));
        ProtocolConfig second = ProtocolConfig.newConfig("http", "https", Map.of("contextPath", "/inventory"));
        Object firstDefinition = new Object();
        Object secondDefinition = new Object();
        ObjectNode firstDescription = JsonNodeFactory.instance.objectNode().put("definition", "orders");
        ObjectNode secondDescription = JsonNodeFactory.instance.objectNode().put("definition", "inventory");

        // 默认路径按当前配置合并，构建绑定不发布端点，也不覆盖共享声明。
        ProtocolBinding orders = meta.build(first, firstDefinition, firstDescription);
        ProtocolBinding inventory = meta.build(second, secondDefinition, secondDescription);
        assertNotSame(orders, inventory);
        assertEquals(new ContextPath(List.of("/orders"), false), orders.getContextPath());
        assertEquals(new ContextPath(List.of("/inventory"), false), inventory.getContextPath());
        assertSame(firstDefinition, orders.getDefinition());
        assertSame(secondDefinition, inventory.getDefinition());
        assertSame(firstDescription, orders.getDescription());
        assertSame(secondDescription, inventory.getDescription());
        assertEquals("orders", orders.getDescription().path("definition").asText());
        assertEquals("inventory", inventory.getDescription().path("definition").asText());
        assertFalse(orders.getDescription().path("contextExact").asBoolean());
        assertFalse(inventory.getDescription().path("contextExact").asBoolean());
        assertNull(meta.getContextPath());
        assertTrue(meta.getEndpoints().isEmpty());
    }

    @Test
    public void declaredPrefixesOverrideTheEndpointDefault() {
        // 服务显式发布到多个目录，端点另有默认目录。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ContextPath declared = new ContextPath(List.of("/orders", "/inventory"), false);
        ProtocolMeta meta = new ProtocolMeta(service, declared, null, Map.of());
        ProtocolConfig config = ProtocolConfig.newConfig("http", "http", Map.of("contextPath", "/default"));

        // 完整采用声明中的目录，不隐含加入端点默认目录。
        ProtocolBinding binding = meta.build(config, null, null);
        assertSame(declared, binding.getContextPath());
        assertEquals(List.of("/orders", "/inventory"), List.copyOf(binding.getContextPath().getPaths()));
        assertSame(declared, meta.getContextPath());
    }

    @Test
    public void exactDeclarationsKeepTheirMatchingModeAndTrailingSlash() {
        // 精确服务地址与默认目录是不同的发布方式。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ContextPath declared = new ContextPath(List.of("/orders/echo/"), true);
        ProtocolMeta meta = new ProtocolMeta(service, declared, null, Map.of());
        ProtocolConfig config = ProtocolConfig.newConfig("http", "http", Map.of("contextPath", "/default"));

        // 构建不把精确地址转换为目录，也不移除有效的尾斜杠。
        ObjectNode description = JsonNodeFactory.instance.objectNode();
        ProtocolBinding binding = meta.build(config, null, description);
        assertSame(declared, binding.getContextPath());
        assertTrue(binding.getContextPath().isExact());
        assertTrue(binding.getDescription().path("contextExact").asBoolean());
        assertEquals(List.of("/orders/echo/"), List.copyOf(binding.getContextPath().getPaths()));
    }

    @Test
    public void aProtocolWithoutHttpContextsCanAcceptAServiceWithoutAdditionalDefinition() {
        // 二进制协议没有 HTTP 路径，也无需单独准备定义对象。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ProtocolConfig config = ProtocolConfig.newConfig("rsf", "rsf", Map.of());

        // 非空绑定本身代表接受服务，路径和定义均允许为空。
        ObjectNode description = JsonNodeFactory.instance.objectNode();
        ProtocolBinding binding = meta.build(config, null, description);
        assertNotNull(binding);
        assertNull(binding.getContextPath());
        assertNull(binding.getDefinition());
        assertFalse(binding.getDescription().path("contextExact").asBoolean());
    }

    @Test
    public void additionalContextsDoNotBecomeAnImplicitDefault() {
        // 协议仅声明额外目录，服务没有显式选择，也没有默认目录。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ProtocolConfig config = ProtocolConfig.newConfig("http", "http", Map.of("contextPaths", "[\"/extra\"]"));

        // 构建保持未选择状态，不把额外挂载擅自用作默认入口。
        ProtocolBinding binding = meta.build(config, null, null);
        assertNull(binding.getContextPath());
        assertNull(meta.getContextPath());
    }

    @Test
    public void invalidDefaultPathsFailWithoutChangingPublicationState() {
        // 当前端点提供了相对路径，不能作为 HTTP 发布目录。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ProtocolConfig config = ProtocolConfig.newConfig("http", "http", Map.of("contextPath", "relative"));
        ObjectNode description = JsonNodeFactory.instance.objectNode();

        // 构建失败保留原始声明与空端点结果，交由外层撤销本轮发布。
        assertThrows(IllegalArgumentException.class, () -> meta.build(config, null, description));
        assertTrue(description.isEmpty());
        assertNull(meta.getContextPath());
        assertTrue(meta.getEndpoints().isEmpty());
    }
}
