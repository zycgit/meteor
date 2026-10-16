/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
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
import net.hasor.meteor.address.dsl.XmlRuleParser;
import net.hasor.meteor.address.provider.AddressProvider;
import net.hasor.meteor.address.provider.InstanceAddressProvider;
import net.hasor.meteor.address.provider.PoolAddressProvider;
import net.hasor.meteor.container.MetContainer;
import net.hasor.meteor.domain.MetRequestObject;
import net.hasor.meteor.domain.MetResponseObject;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.rpc.MetCaller;
import net.hasor.meteor.rpc.MetClientImpl;
import net.hasor.meteor.rpc.MetRequestLocal;
import net.hasor.meteor.rpc.MetResponseLocal;
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

        // 序列化契约与内置实现。
        types.addAll(List.of(SerializeCoder.class, SerializeFactory.class));
        types.add(JavaSerializeCoder.class);
        types.add(JsonSerializeCoder.class);
        types.add(HessianSerializeCoder.class);

        // 地址存储、提供者与 XML 规则解析。
        types.addAll(List.of(AddressPool.class, DiskCache.class, InterAddress.class, XmlRuleParser.class));
        types.add(AddressProvider.class);
        types.add(InstanceAddressProvider.class);
        types.add(PoolAddressProvider.class);

        // 调用契约、调度实现及容器。
        types.addAll(List.of(MetContext.class, MetRequest.class, RequestPayload.class));
        types.addAll(List.of(MetRequestObject.class, MetResponseObject.class, ServiceDomain.class));
        types.addAll(List.of(MetCaller.class, MetClientImpl.class, MetContainer.class));
        types.addAll(List.of(MetRequestLocal.class, MetResponseLocal.class));

        // 每个类只能存在一份定义，且应与框架入口位于同一个产物。
        ClassLoader loader = this.getClass().getClassLoader();
        URL framework = MetContextImpl.class.getProtectionDomain().getCodeSource().getLocation();
        for (Class<?> type : types) {
            String resource = type.getName().replace('.', '/') + ".class";
            List<URL> definitions = Collections.list(loader.getResources(resource));
            assertEquals(type.getName(), 1, definitions.size());
            URL location = type.getProtectionDomain().getCodeSource().getLocation();
            assertEquals(type.getName(), framework, location);
        }
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.hasor.meteor.InterAddress"));
    }

    @Test
    public void runtimeClasspathContainsNoHasorContainerOrLegacyUtilities() throws Exception {
        // 插件引入后仍不应带回旧容器、工具包或控制台依赖。
        List<String> legacyTypes = new ArrayList<>();
        legacyTypes.add("net.hasor.core.AppContext");
        legacyTypes.add("net.hasor.core.Hasor");
        legacyTypes.add("net.hasor.utils.StringUtils");
        legacyTypes.add("net.hasor.tconsole.TelCommand");

        // 逐项检查旧入口均不可加载，旧容器的资源描述也不应被带入。
        for (String name : legacyTypes) {
            assertThrows("Unexpected dependency: " + name, ClassNotFoundException.class, () -> Class.forName(name));
        }
        assertNull(this.getClass().getClassLoader().getResource("META-INF/hasor.schemas"));
    }

    @Test
    public void originalWireTypeDescriptorsResolvePrimitivesAndArrays() throws Exception {
        // 基本类型、普通引用和数组分别覆盖类名及线协议描述符两种入口。
        List<Class<?>> types = new ArrayList<>();
        types.addAll(List.of(int.class, boolean.class, byte.class, short.class));
        types.addAll(List.of(long.class, float.class, double.class, char.class));
        types.addAll(List.of(String.class, int[].class, String[][].class));

        // 相同类型无论采用类名还是线协议描述符，都应还原为原始 Class。
        ClassLoader loader = this.getClass().getClassLoader();
        for (Class<?> type : types) {
            String descriptor = MetRuntimeUtils.toAsmType(type);
            assertSame(type, MetRuntimeUtils.getType(descriptor, loader));
            assertSame(type, MetRuntimeUtils.getType(type.getName(), loader));
        }
    }

    @Test
    public void publisherPublicApiDoesNotExposeContainerTypes() {
        // 对外契约及发布器的嵌套类型都属于需要审查的 API 边界。
        List<Class<?>> contracts = List.of(MetPublisher.class, MetContext.class, MetBindInfo.class, MetSettings.class);
        List<Class<?>> types = new ArrayList<>(contracts);
        types.addAll(Arrays.asList(MetPublisher.class.getDeclaredClasses()));

        // 泛型签名同时覆盖参数与返回类型，确保没有泄漏旧容器类型。
        for (Class<?> type : types) {
            for (Method method : type.getMethods()) {
                String signature = method.toGenericString();
                assertFalse(signature, signature.contains("net.hasor.core"));
            }
        }
    }
}
