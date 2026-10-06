/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.bootstrap;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** The framework must be usable without any optional protocol plugin on its runtime classpath. */
public class StandaloneConfigurationTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void defaultsUseAnAvailableCoderWithoutOpeningAListener() throws Exception {
        try (RsfContext context = new Configuration().buildContext()) {
            assertEquals("Java", context.getSettings().getSerializeType());
            assertTrue(context.getSerializeCoder("Java") instanceof JavaSerializeCoder);
            assertNull(context.getSerializeCoder("Hprose"));
            assertNull(this.getClass().getClassLoader().getResource("net/hasor/rsf/protocol/hprose/HproseProtocol.class"));
            assertNull(context.bindAddress("rsf"));
            assertTrue(context.runProtocols().isEmpty());
            assertFalse(context.isOnline());
        }
    }

    @Test
    public void defaultSerializationCompletesRealRpcWithoutPlugins() throws Exception {
        Configuration serverConfig = new Configuration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        Configuration clientConfig = new Configuration();
        clientConfig.connector("tcp").protocol("rsf");
        try (RsfContext server = serverConfig.buildContext(); RsfContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Echo.class, value -> "remote:" + value).register();
            RsfBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();
            assertEquals("remote:value", client.getRsfClient(server.bindAddress("rsf")).getRemote(service).echo("value"));
            assertNull(client.bindAddress("rsf"));
        }
    }
}
