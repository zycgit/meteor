/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;

import java.util.*;
import java.util.concurrent.*;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.rsf.domain.payload.RequestPayload;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorManagerTest {
    @Test
    public void initializationDiscoversFactoriesWithoutCreatingEndpoints() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("a", "b")) {
            assertEquals(0, fixture.created);
            assertEquals(2, fixture.manager.configurations().size());
            fixture.manager.bind("a").get();
            assertEquals(1, fixture.created);
            assertNull(fixture.manager.find("b"));
            assertSame(fixture.manager.bind("a").get(), fixture.manager.bind("a").get());
            assertEquals(1, fixture.endpoints.get("a").starts);
        }
    }

    @Test
    public void concurrentFirstUseCreatesOneConnector() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("a")) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<RsfListen>> calls = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    calls.add(pool.submit(() -> fixture.manager.bind("a").get()));
                }
                RsfListen first = calls.get(0).get();
                for (Future<RsfListen> call : calls) {
                    assertSame(first, call.get(2, TimeUnit.SECONDS));
                }
                assertEquals(1, fixture.created);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    public void operationsRequireInitAndCannotCreateUnconfiguredEndpoints() throws Exception {
        ConnectorManager manager = new ConnectorManager(ConnectorResourcesTest.sharedContext());
        assertNotNull(ConnectorConnectionsTest.failure(manager.bind("missing")));
        assertNotNull(ConnectorConnectionsTest.failure(manager.connect(EndpointFixture.address("a"))));
        try {
            manager.subscribe((channel, id, payload) -> {});
            fail();
        } catch (IllegalStateException expected) {
        }
        manager.init();
        assertNotNull(ConnectorConnectionsTest.failure(manager.bind("missing")));
        manager.close();
    }

    @Test
    public void duplicateSchemeAcrossEndpointsIsRejected() {
        ConnectorConfig a = EndpointFixture.config("a");
        ConnectorConfig b = new ConnectorConfig("b", a.address(), Collections.singletonMap("listenType", "memory"));
        try (ConnectorManager manager = new ConnectorManager(ConnectorResourcesTest.sharedContext(a, b))) {
            try {
                manager.init();
                fail();
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("multiple endpoints"));
            }
        }
    }

    @Test
    public void mountedSchemesSelectTheSameEndpoint() throws Exception {
        ConnectorConfig config = new ConnectorConfig("shared", EndpointFixture.address("memory"), Collections.singletonMap("listenType", "memory"),
                Arrays.asList(new ProtocolConfig("one", "", Collections.emptyMap()), new ProtocolConfig("two", "", Collections.emptyMap())));
        try (TestConnectorManager manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext(config))) {
            manager.prepare(config, (cfg, mgr) -> new EndpointFixture.Endpoint(cfg, mgr, new ArrayList<>()));
            manager.init();
            RsfChannel first = manager.connect(EndpointFixture.address("ONE")).get();
            RsfChannel second = manager.connect(EndpointFixture.address("two")).get();
            assertSame(first.getConnector(), second.getConnector());
            assertSame(manager.find("one"), manager.find("two"));
        }
    }
}
