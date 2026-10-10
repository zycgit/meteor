/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.hasor.meteor.*;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.DiskCache;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.provider.AddressProvider;
import net.hasor.meteor.address.provider.InstanceAddressProvider;
import net.hasor.meteor.address.provider.PoolAddressProvider;
import net.hasor.meteor.address.route.GroovyRuleScriptEngine;
import net.hasor.meteor.bootstrap.MetContextImpl;
import net.hasor.meteor.container.MetContainer;
import net.hasor.meteor.domain.MetRequestObject;
import net.hasor.meteor.domain.MetResponseObject;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.rpc.MetCaller;
import net.hasor.meteor.rpc.MetClientImpl;
import net.hasor.meteor.rpc.filters.local.LocalPref;
import net.hasor.meteor.rpc.filters.online.OnlineMetFilter;
import net.hasor.meteor.rpc.filters.thread.LocalWarpFilter;
import net.hasor.meteor.rpc.filters.thread.MetRequestLocal;
import net.hasor.meteor.rpc.filters.thread.MetResponseLocal;
import net.hasor.meteor.serialize.SerializeCoder;
import net.hasor.meteor.serialize.SerializeFactory;
import net.hasor.meteor.serialize.coder.HessianSerializeCoder;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import net.hasor.meteor.serialize.coder.JsonSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

public class BootstrapBoundaryTest {
    @Test
    public void frameworkClassesHaveASingleOwner() throws Exception {
        // 插件仅提供协议能力；基础契约、容器、地址本和调用链仍来自同一个框架组件。
        List<Class<?>> types = new ArrayList<>();
        types.addAll(List.of(SerializeCoder.class, SerializeFactory.class, JavaSerializeCoder.class, JsonSerializeCoder.class, HessianSerializeCoder.class));
        types.addAll(List.of(AddressPool.class, DiskCache.class, InterAddress.class, AddressProvider.class, InstanceAddressProvider.class, PoolAddressProvider.class, GroovyRuleScriptEngine.class));
        types.addAll(List.of(MetContext.class, MetRequest.class, RequestPayload.class, MetRequestObject.class, MetResponseObject.class, ServiceDomain.class, MetCaller.class, MetClientImpl.class));
        types.addAll(List.of(LocalPref.class, OnlineMetFilter.class, LocalWarpFilter.class, MetRequestLocal.class, MetResponseLocal.class, MetContainer.class));
        ClassLoader loader = this.getClass().getClassLoader();
        URL framework = MetContextImpl.class.getProtectionDomain().getCodeSource().getLocation();
        for (Class<?> type : types) {
            String resource = type.getName().replace('.', '/') + ".class";
            List<URL> definitions = Collections.list(loader.getResources(resource));
            assertEquals(type.getName(), 1, definitions.size());
            assertEquals(type.getName(), framework, type.getProtectionDomain().getCodeSource().getLocation());
        }
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.hasor.meteor.InterAddress"));
    }

    @Test
    public void runtimeClasspathContainsNoHasorContainerOrLegacyUtilities() throws Exception {
        // 插件引入后仍不应带回旧容器、工具包或控制台依赖。
        List<String> legacyTypes = List.of("net.hasor.core.AppContext", "net.hasor.core.Hasor", "net.hasor.utils.StringUtils", "net.hasor.tconsole.TelCommand");
        for (String name : legacyTypes) {
            assertThrows("Unexpected dependency: " + name, ClassNotFoundException.class, () -> Class.forName(name));
        }
        assertNull(this.getClass().getClassLoader().getResource("META-INF/hasor.schemas"));
    }

    @Test
    public void originalWireTypeDescriptorsResolvePrimitivesAndArrays() throws Exception {
        List<Class<?>> types = List.of(int.class, boolean.class, byte.class, short.class, long.class, float.class, double.class, char.class, String.class, int[].class, String[][].class);
        for (Class<?> type : types) {
            String descriptor = MetRuntimeUtils.toAsmType(type);
            assertSame(type, MetRuntimeUtils.getType(descriptor, this.getClass().getClassLoader()));
            assertSame(type, MetRuntimeUtils.getType(type.getName(), this.getClass().getClassLoader()));
        }
    }

    @Test
    public void publisherPublicApiDoesNotExposeContainerTypes() {
        List<Class<?>> contracts = List.of(MetPublisher.class, MetContext.class, MetBindInfo.class, MetSettings.class);
        List<Class<?>> types = new ArrayList<>(contracts);
        types.addAll(Arrays.asList(MetPublisher.class.getDeclaredClasses()));
        for (Class<?> type : types) {
            for (Method method : type.getMethods()) {
                String signature = method.toGenericString();
                assertFalse(signature, signature.contains("net.hasor.core"));
            }
        }
    }
}
