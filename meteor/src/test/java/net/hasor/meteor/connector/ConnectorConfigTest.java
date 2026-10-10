/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import net.hasor.meteor.address.InterAddress;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorConfigTest {
    @Test
    public void snapshotsDetachOptionsAndRoutesFromTheirBuilders() {
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "TCP");
        options.put("connectTimeout", "1200");
        Map<String, String> protocolOptions = new HashMap<>();
        protocolOptions.put("maxFrameSize", "1024");
        ProtocolConfig protocol = new ProtocolConfig("rsf", "RSF", "rsf", protocolOptions);
        List<ProtocolConfig> protocols = new ArrayList<>(Collections.singletonList(protocol));
        ConnectorConfig config = new ConnectorConfig("endpoint", EndpointFixture.address("tcp"), options, protocols, false);
        options.put("connectTimeout", "2400");
        protocolOptions.put("maxFrameSize", "2048");
        protocols.clear();
        assertEquals(1200, config.connectTimeout());
        assertEquals("1024", config.protocol("RsF").option("maxFrameSize", null));
        assertEquals(1, config.protocols().size());
        for (Runnable mutation : Arrays.<Runnable>asList(() -> config.options().clear(), () -> config.protocols().clear(), () -> protocol.options().clear())) {
            try {
                mutation.run();
                fail("Runtime configuration must be immutable");
            } catch (UnsupportedOperationException expected) {
            }
        }
    }

    @Test
    public void changingTheTargetAddressRetainsProtocolMountsAndEndpointOptions() {
        Map<String, String> options = Map.of("listenType", "tcp", "limit", "10", "workerThread", "2");
        ProtocolConfig first = new ProtocolConfig("one", "one", Map.of("limit", "20"));
        ProtocolConfig second = new ProtocolConfig("two", "two", Collections.emptyMap());
        ConnectorConfig config = new ConnectorConfig("endpoint", EndpointFixture.address("tcp"), options, Arrays.asList(first, second), false);
        // 出站连接更换目标地址，继续使用端点已准备的协议挂载。
        InterAddress remote = new InterAddress("tcp", "127.0.0.1", 2182, "other");
        ConnectorConfig copy = config.withAddress(remote);
        assertEquals(remote, copy.address());
        assertEquals(config.name(), copy.name());
        assertFalse(copy.bindEnabled());
        assertSame(first, copy.protocol("ONE"));
        assertEquals(options, copy.options());
        assertNotEquals(remote, config.address());
    }

    @Test
    public void emptyAndCaseInsensitiveDuplicateMountsAreRejected() {
        ProtocolConfig first = new ProtocolConfig("first", "rsf", "rsf", Collections.emptyMap());
        ProtocolConfig second = new ProtocolConfig("second", "RSF", "rsf", Collections.emptyMap());
        List<List<ProtocolConfig>> invalid = Arrays.asList(Collections.emptyList(), Arrays.asList(first, second));
        for (List<ProtocolConfig> protocols : invalid) {
            try {
                new ConnectorConfig("endpoint", EndpointFixture.address("tcp"), Map.of("listenType", "tcp"), protocols, true);
                fail("Empty or ambiguous routes must fail before opening a connector");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains(protocols.isEmpty() ? "at least one" : "Duplicate"));
            }
        }
    }
}
