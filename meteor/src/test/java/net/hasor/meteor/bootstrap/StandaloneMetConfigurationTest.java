/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** The framework must be usable without any optional protocol plugin on its runtime classpath. */
public class StandaloneMetConfigurationTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void defaultsUseAnAvailableCoderWithoutOpeningAListener() throws Exception {
        try (MetContext context = new MetConfiguration().buildContext()) {
            assertEquals("application", context.getApplicationName());
            assertEquals("application", context.getSettings().getApplicationName());
            assertEquals("Java", context.getSettings().getSerializeType());
            assertTrue(context.getSerializeCoder("Java") instanceof JavaSerializeCoder);
            assertNull(context.getSerializeCoder("Hprose"));
            assertNull(this.getClass().getClassLoader().getResource("net/hasor/meteor/protocol/hprose/HproseProtocol.class"));
            assertNull(context.bindAddress("rsf"));
            assertTrue(context.runProtocols().isEmpty());
            assertFalse(context.isOnline());
        }
    }

    @Test
    public void defaultSerializationCompletesRealRpcWithoutPlugins() throws Exception {
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("tcp").protocol("rsf");
        try (MetContext server = serverConfig.buildContext(); MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Echo.class, value -> "remote:" + value).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();
            assertEquals("remote:value", client.getRsfClient(server.bindAddress("rsf")).getRemote(service).echo("value"));
            assertNull(client.bindAddress("rsf"));
        }
    }

    @Test
    public void absentPluginIsRejectedRatherThanSubstitutedByTheBuiltinProtocol() throws Exception {
        MetConfiguration config = new MetConfiguration();
        config.connector("http").protocol("hprose");
        try {
            config.buildContext();
            fail("The framework must not silently replace an unavailable protocol");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unknown protocol"));
            assertTrue(expected.getMessage().contains("hprose"));
        }
    }

    @Test
    public void builtSettingsDoNotRediscoverSpiThroughAnotherThreadClassLoader() throws Exception {
        MetConfiguration source = new MetConfiguration();
        source.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader unavailable = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) {
                throw new AssertionError("A built configuration must use its prepared SPI snapshot: " + name);
            }
        };
        try (MetContext context = source.buildContext()) {
            try {
                thread.setContextClassLoader(unavailable);
                assertEquals(Collections.singleton("rsf"), context.getSettings().getProtocols());
                assertEquals("rsf", context.getSettings().getDefaultProtocol());
                assertEquals(0, context.getSettings().getBindAddressSet("rsf").getPort());
                assertEquals(1, context.getSettings().getConnectorConfigs().size());
            } finally {
                thread.setContextClassLoader(original);
            }
        }
    }

    @Test
    public void applicationNameBelongsToEachContextConfigurationSnapshot() throws Exception {
        MetConfiguration source = new MetConfiguration().setApplicationName("orders");
        try (MetContext orders = source.buildContext()) {
            source.setApplicationName("inventory");
            try (MetContext inventory = source.buildContext()) {
                assertEquals("orders", orders.getApplicationName());
                assertEquals("orders", orders.getSettings().getApplicationName());
                assertEquals("inventory", inventory.getApplicationName());
            }
            source.setApplicationName(" ");
            try (MetContext defaults = source.buildContext()) {
                assertEquals("application", defaults.getApplicationName());
            }
            source.setApplicationName(null);
            assertEquals("application", source.getApplicationName());
            assertEquals("orders", orders.getApplicationName());
        }
    }

    @Test
    public void editingRuntimeOptionsDoesNotMutateTheBuilderOrAnotherContext() throws Exception {
        MetConfiguration source = new MetConfiguration().setRequestOption("trace", "initial").setResponseOption("tag", "initial");
        try (MetContext first = source.buildContext(); MetContext second = source.buildContext()) {
            first.getSettings().getRequestOptions().addOption("trace", "first");
            first.getSettings().getResponseOptions().removeOption("tag");
            assertEquals("initial", source.getRequestOptions().getOption("trace"));
            assertEquals("initial", second.getSettings().getRequestOptions().getOption("trace"));
            assertEquals("initial", source.getResponseOptions().getOption("tag"));
            assertEquals("initial", second.getSettings().getResponseOptions().getOption("tag"));
            source.setRequestOption("trace", "later");
            assertEquals("first", first.getSettings().getRequestOptions().getOption("trace"));
            assertEquals("initial", second.getSettings().getRequestOptions().getOption("trace"));
        }
    }
}
