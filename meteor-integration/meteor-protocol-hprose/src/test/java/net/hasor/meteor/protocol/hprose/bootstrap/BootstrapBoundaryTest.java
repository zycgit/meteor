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
    public void builtinSerializationClassesComeOnlyFromTheFrameworkComponent() throws Exception {
        ClassLoader loader = this.getClass().getClassLoader();
        for (Class<?> type : Arrays.asList(SerializeCoder.class, SerializeFactory.class, JavaSerializeCoder.class, JsonSerializeCoder.class, HessianSerializeCoder.class)) {
            assertEquals(1, Collections.list(loader.getResources(type.getName().replace('.', '/') + ".class")).size());
            assertEquals(RequestPayload.class.getProtectionDomain().getCodeSource().getLocation(), type.getProtectionDomain().getCodeSource().getLocation());
            assertEquals(MetContextImpl.class.getProtectionDomain().getCodeSource().getLocation(), type.getProtectionDomain().getCodeSource().getLocation());
        }
    }

    @Test
    public void addressAndApiClassesComeOnlyFromTheFrameworkComponent() throws Exception {
        ClassLoader loader = this.getClass().getClassLoader();
        for (Class<?> type : Arrays.asList(AddressPool.class, DiskCache.class, InterAddress.class, AddressProvider.class, InstanceAddressProvider.class, PoolAddressProvider.class, GroovyRuleScriptEngine.class, MetContext.class, MetRequest.class, RequestPayload.class, MetRequestObject.class, MetResponseObject.class, ServiceDomain.class)) {
            assertEquals(1, Collections.list(loader.getResources(type.getName().replace('.', '/') + ".class")).size());
            assertEquals(RequestPayload.class.getProtectionDomain().getCodeSource().getLocation(), type.getProtectionDomain().getCodeSource().getLocation());
            assertEquals(MetContextImpl.class.getProtectionDomain().getCodeSource().getLocation(), type.getProtectionDomain().getCodeSource().getLocation());
        }
        try {
            Class.forName("net.hasor.meteor.InterAddress");
            fail("Legacy address model still exists");
        } catch (ClassNotFoundException expected) {
        }
    }

    @Test
    public void rpcClassesComeOnlyFromTheFrameworkComponent() throws Exception {
        URL rpcLocation = MetCaller.class.getProtectionDomain().getCodeSource().getLocation();
        assertEquals(MetContext.class.getProtectionDomain().getCodeSource().getLocation(), rpcLocation);
        for (Class<?> type : Arrays.asList(MetCaller.class, MetClientImpl.class, LocalPref.class, OnlineMetFilter.class, LocalWarpFilter.class, MetRequestLocal.class, MetResponseLocal.class)) {
            assertEquals(1, Collections.list(this.getClass().getClassLoader().getResources(type.getName().replace('.', '/') + ".class")).size());
            assertEquals(rpcLocation, type.getProtectionDomain().getCodeSource().getLocation());
        }
        assertEquals(MetContextImpl.class.getProtectionDomain().getCodeSource().getLocation(), rpcLocation);
    }

    @Test
    public void containerClassesComeOnlyFromTheFrameworkComponent() throws Exception {
        URL containerLocation = MetContainer.class.getProtectionDomain().getCodeSource().getLocation();
        assertEquals(MetContextImpl.class.getProtectionDomain().getCodeSource().getLocation(), containerLocation);
        assertEquals(MetContext.class.getProtectionDomain().getCodeSource().getLocation(), containerLocation);
        for (Class<?> type : Arrays.asList(MetContainer.class)) {
            assertEquals(1, Collections.list(this.getClass().getClassLoader().getResources(type.getName().replace('.', '/') + ".class")).size());
            assertEquals(containerLocation, type.getProtectionDomain().getCodeSource().getLocation());
        }
    }

    @Test
    public void runtimeClasspathContainsNoHasorContainerOrLegacyUtilities() throws Exception {
        for (String name : Arrays.asList("net.hasor.core.AppContext", "net.hasor.core.Hasor", "net.hasor.utils.StringUtils", "net.hasor.tconsole.TelCommand")) {
            try {
                Class.forName(name);
                fail("Unexpected dependency: " + name);
            } catch (ClassNotFoundException expected) {
            }
        }
        assertNull(this.getClass().getClassLoader().getResource("META-INF/hasor.schemas"));
    }

    @Test
    public void originalWireTypeDescriptorsResolvePrimitivesAndArrays() throws Exception {
        for (Class<?> type : new Class<?>[] { int.class, boolean.class, byte.class, short.class, long.class, float.class, double.class, char.class, String.class, int[].class, String[][].class }) {
            assertSame(type, MetRuntimeUtils.getType(MetRuntimeUtils.toAsmType(type), this.getClass().getClassLoader()));
            assertSame(type, MetRuntimeUtils.getType(type.getName(), this.getClass().getClassLoader()));
        }
    }

    @Test
    public void publisherPublicApiDoesNotExposeContainerTypes() {
        List<Class<?>> types = new ArrayList<>(Arrays.asList(MetPublisher.class, MetContext.class, MetBindInfo.class, MetSettings.class));
        types.addAll(Arrays.asList(MetPublisher.class.getDeclaredClasses()));
        for (Class<?> type : types) {
            for (Method method : type.getMethods()) {
                assertFalse(method.toGenericString(), method.toGenericString().contains("net.hasor.core"));
            }
        }
    }
}
