/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift;
import java.lang.reflect.Method;
import java.util.Map;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.example.protocol.thrift.generated.InteropService;
import org.apache.thrift.ProcessFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ThriftServiceResourceTest {
    @Test
    public void generatedServiceExposesNativeProcessorMetadata() {
        // 资源直接读取 SDK 生成的服务和 Processor 元数据，不需要业务实现。
        ThriftServiceResource resource = new ThriftServiceResource(InteropService.class);

        // 方法表与 Processor 方法表一致，并保留 IDL 的 oneway 标记。
        assertSame(InteropService.class, resource.getGeneratedService());
        assertSame(InteropService.Iface.class, resource.getIfaceType());
        assertEquals("InteropService", resource.getServiceName());
        assertEquals(16, resource.getMethods().size());
        assertEquals(resource.getMethods().keySet(), resource.getFunctions().keySet());
        assertTrue(resource.getFunctions().get("notify").isOneway());
        assertFalse(resource.getFunctions().get("ping").isOneway());
        assertTrue(resource.getFunctions().get("add").getEmptyArgsInstance() instanceof InteropService.add_args);
    }

    @Test
    public void extendedGeneratedServiceIncludesInheritedMethods() throws Exception {
        // 子服务的 Processor 同时包含父服务方法和本地声明的方法。
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);

        // 继承方法仍指向父 Iface，本地方法指向子 Iface。
        Method inherited = InteropService.Iface.class.getMethod("echo", String.class);
        Method local = ExtendedInteropService.Iface.class.getMethod("extended", String.class);
        assertSame(ExtendedInteropService.Iface.class, resource.getIfaceType());
        assertEquals(17, resource.getFunctions().size());
        assertEquals(inherited, resource.getMethods().get("echo"));
        assertEquals(local, resource.getMethods().get("extended"));
        assertTrue(resource.getFunctions().get("echo").getEmptyArgsInstance() instanceof InteropService.echo_args);
        Object extendedArguments = resource.getFunctions().get("extended").getEmptyArgsInstance();
        assertTrue(extendedArguments instanceof ExtendedInteropService.extended_args);
    }

    @Test
    public void processorMetadataCannotBeModifiedByCallers() {
        // 读取资源公开的 Processor 元数据快照。
        ThriftServiceResource resource = new ThriftServiceResource(InteropService.class);
        Map<String, ProcessFunction<?, ?, ?>> functions = resource.getFunctions();

        // 调用方不能移除协议编码所需的方法。
        assertThrows(UnsupportedOperationException.class, () -> functions.remove("echo"));
        assertTrue(resource.getFunctions().containsKey("echo"));
    }

    @Test
    public void methodMetadataCannotBeModifiedByCallers() {
        // 读取资源公开的 Java 方法元数据快照。
        ThriftServiceResource resource = new ThriftServiceResource(InteropService.class);
        Map<String, Method> methods = resource.getMethods();

        // 方法表与 Processor 表的对应关系不能被外部改写。
        assertThrows(UnsupportedOperationException.class, () -> methods.remove("echo"));
        assertTrue(resource.getMethods().containsKey("echo"));
    }

    @Test
    public void ordinaryJavaTypeCannotBeUsedAsGeneratedService() {
        // 普通 Java 类型没有生成器定义的 Iface 和 Processor。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new ThriftServiceResource(String.class));
        assertTrue(failure.getMessage().contains(String.class.getName()));
    }

    @Test
    public void generatedIfaceCannotBeUsedInsteadOfItsEnclosingService() {
        // 资源入口要求生成服务外层类，不能误传内部 Iface。
        assertThrows(IllegalArgumentException.class, () -> new ThriftServiceResource(InteropService.Iface.class));
    }
}
