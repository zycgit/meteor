/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.ref.Tuple;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfException;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** Shared runtime fixtures use the public configuration and SPI contracts. */
public class ConnectorResourcesTest {
    static RsfContext sharedContext(ConnectorConfig... configs) {
        return sharedContext(new TestConnectorManager.TestLoader(ConnectorResourcesTest.class.getClassLoader()), configs);
    }

    static RsfContext sharedContext(ClassLoader loader, ConnectorConfig... configs) {
        JavaSerializeCoder coder = new JavaSerializeCoder();
        RsfSettings settings = (RsfSettings) Proxy.newProxyInstance(loader, new Class<?>[] { RsfSettings.class }, (proxy, method, args) -> {
            if ("getConnectorConfigs".equals(method.getName())) {
                return Arrays.asList(configs);
            }
            if ("getDefaultTimeout".equals(method.getName())) {
                return 3000;
            }
            throw new AssertionError("Unexpected setting: " + method);
        });
        return (RsfContext) Proxy.newProxyInstance(loader, new Class<?>[] { RsfContext.class }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getClassLoader":
                    return loader;
                case "getSettings":
                    return settings;
                case "getSerializeCoder":
                    return "Java".equals(args[0]) ? coder : null;
                case "getServiceIDs":
                    return Collections.emptyList();
                case "getServiceInfo":
                    return null;
                default:
                    throw new AssertionError("Unexpected context access: " + method);
            }
        });
    }


    @Test
    public void timerRequiresInitializationAndStopsOnClose() throws Exception {
        ConnectorManager manager = new ConnectorManager(sharedContext());
        try {
            manager.schedule(() -> {}, 1);
            fail();
        } catch (RejectedExecutionException expected) {
        }
        manager.init();
        CountDownLatch fired = new CountDownLatch(1);
        manager.schedule(fired::countDown, 1);
        assertTrue(fired.await(2, TimeUnit.SECONDS));
        manager.close();
        try {
            manager.schedule(() -> {}, 1);
            fail();
        } catch (RejectedExecutionException expected) {
        }
    }

    @Test
    public void idsAreUniqueAcrossManagerRestart() {
        ConnectorManager manager = new ConnectorManager(sharedContext());
        manager.init();
        long first = manager.nextConnectionId();
        manager.close();
        manager.init();
        assertTrue(manager.nextConnectionId() > first);
        manager.close();
    }
}
