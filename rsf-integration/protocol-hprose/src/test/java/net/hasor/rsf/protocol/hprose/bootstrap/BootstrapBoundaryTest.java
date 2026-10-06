/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.hprose.bootstrap;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.hasor.rsf.*;
import net.hasor.rsf.address.AddressPool;
import net.hasor.rsf.address.DiskCache;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.address.provider.AddressProvider;
import net.hasor.rsf.address.provider.InstanceAddressProvider;
import net.hasor.rsf.address.provider.PoolAddressProvider;
import net.hasor.rsf.address.route.GroovyRuleScriptEngine;
import net.hasor.rsf.bootstrap.RsfContextImpl;
import net.hasor.rsf.container.RsfContainer;
import net.hasor.rsf.domain.RsfRequestObject;
import net.hasor.rsf.domain.RsfResponseObject;
import net.hasor.rsf.domain.RsfRuntimeUtils;
import net.hasor.rsf.domain.ServiceDomain;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.rpc.RsfCaller;
import net.hasor.rsf.rpc.RsfClientImpl;
import net.hasor.rsf.rpc.filters.local.LocalPref;
import net.hasor.rsf.rpc.filters.online.OnlineRsfFilter;
import net.hasor.rsf.rpc.filters.thread.LocalWarpFilter;
import net.hasor.rsf.rpc.filters.thread.RsfRequestLocal;
import net.hasor.rsf.rpc.filters.thread.RsfResponseLocal;
import net.hasor.rsf.serialize.SerializeCoder;
import net.hasor.rsf.serialize.SerializeFactory;
import net.hasor.rsf.serialize.coder.HessianSerializeCoder;
import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import net.hasor.rsf.serialize.coder.JsonSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

public class BootstrapBoundaryTest {
    @Test
    public void builtinSerializationClassesComeOnlyFromTheFrameworkComponent() throws Exception {
        ClassLoader loader = this.getClass().getClassLoader();
        for (Class<?> type : Arrays.asList(SerializeCoder.class, SerializeFactory.class, JavaSerializeCoder.class, JsonSerializeCoder.class, HessianSerializeCoder.class)) {
            assertEquals(1, Collections.list(loader.getResources(type.getName().replace('.', '/') + ".class")).size());
            assertEquals(RequestPayload.class.getProtectionDomain().getCodeSource().getLocation(), type.getProtectionDomain().getCodeSource().getLocation());
            assertEquals(RsfContextImpl.class.getProtectionDomain().getCodeSource().getLocation(), type.getProtectionDomain().getCodeSource().getLocation());
        }
    }

    @Test
    public void addressAndApiClassesComeOnlyFromTheFrameworkComponent() throws Exception {
        ClassLoader loader = this.getClass().getClassLoader();
        for (Class<?> type : Arrays.asList(AddressPool.class, DiskCache.class, InterAddress.class, AddressProvider.class, InstanceAddressProvider.class, PoolAddressProvider.class, GroovyRuleScriptEngine.class, RsfContext.class, RsfRequest.class, RequestPayload.class, RsfRequestObject.class, RsfResponseObject.class, ServiceDomain.class)) {
            assertEquals(1, Collections.list(loader.getResources(type.getName().replace('.', '/') + ".class")).size());
            assertEquals(RequestPayload.class.getProtectionDomain().getCodeSource().getLocation(), type.getProtectionDomain().getCodeSource().getLocation());
            assertEquals(RsfContextImpl.class.getProtectionDomain().getCodeSource().getLocation(), type.getProtectionDomain().getCodeSource().getLocation());
        }
        try {
            Class.forName("net.hasor.rsf.InterAddress");
            fail("Legacy address model still exists");
        } catch (ClassNotFoundException expected) {
        }
    }

    @Test
    public void rpcClassesComeOnlyFromTheFrameworkComponent() throws Exception {
        URL rpcLocation = RsfCaller.class.getProtectionDomain().getCodeSource().getLocation();
        assertEquals(RsfContext.class.getProtectionDomain().getCodeSource().getLocation(), rpcLocation);
        for (Class<?> type : Arrays.asList(RsfCaller.class, RsfClientImpl.class, LocalPref.class, OnlineRsfFilter.class, LocalWarpFilter.class, RsfRequestLocal.class, RsfResponseLocal.class)) {
            assertEquals(1, Collections.list(this.getClass().getClassLoader().getResources(type.getName().replace('.', '/') + ".class")).size());
            assertEquals(rpcLocation, type.getProtectionDomain().getCodeSource().getLocation());
        }
        assertEquals(RsfContextImpl.class.getProtectionDomain().getCodeSource().getLocation(), rpcLocation);
    }

    @Test
    public void containerClassesComeOnlyFromTheFrameworkComponent() throws Exception {
        URL containerLocation = RsfContainer.class.getProtectionDomain().getCodeSource().getLocation();
        assertEquals(RsfContextImpl.class.getProtectionDomain().getCodeSource().getLocation(), containerLocation);
        assertEquals(RsfContext.class.getProtectionDomain().getCodeSource().getLocation(), containerLocation);
        for (Class<?> type : Arrays.asList(RsfContainer.class)) {
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
            assertSame(type, RsfRuntimeUtils.getType(RsfRuntimeUtils.toAsmType(type), this.getClass().getClassLoader()));
            assertSame(type, RsfRuntimeUtils.getType(type.getName(), this.getClass().getClassLoader()));
        }
    }

    @Test
    public void publisherPublicApiDoesNotExposeContainerTypes() {
        List<Class<?>> types = new ArrayList<>(Arrays.asList(RsfPublisher.class, RsfContext.class, RsfBindInfo.class, RsfSettings.class));
        types.addAll(Arrays.asList(RsfPublisher.class.getDeclaredClasses()));
        for (Class<?> type : types) {
            for (Method method : type.getMethods()) {
                assertFalse(method.toGenericString(), method.toGenericString().contains("net.hasor.core"));
            }
        }
    }
}
