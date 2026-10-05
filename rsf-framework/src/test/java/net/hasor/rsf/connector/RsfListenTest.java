/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import org.junit.Test;
import static org.junit.Assert.*;

/** One endpoint owns one pending bind and one active listener. */
public class RsfListenTest {
    private EndpointFixture.Endpoint endpoint(EndpointFixture fixture) throws Exception {
        fixture.manager.connect(EndpointFixture.address("tcp")).get();
        EndpointFixture.Endpoint endpoint = fixture.endpoints.get("tcp");
        endpoint.binding = new BasicFuture<>();
        return endpoint;
    }

    @Test
    public void pendingBindsShareFutureAndCompleteOnlyWhenUsable() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<RsfListen> first = endpoint.bind();
            assertSame(first, endpoint.bind());
            assertFalse(first.isDone());
            EndpointFixture.Listen listen = new EndpointFixture.Listen(endpoint, fixture.manager);
            endpoint.binding.completed(listen);
            assertSame(listen, first.get());
            assertSame(first, endpoint.bind());
            assertEquals(1, endpoint.binds);
            assertSame(listen, endpoint.getListenList().get(0));
        }
    }

    @Test
    public void cancellationReclaimsLateListenerAndAllowsRetry() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<RsfListen> cancelled = endpoint.bind();
            BasicFuture<RsfListen> operation = endpoint.binding;
            cancelled.cancel();
            endpoint.binding = new BasicFuture<>();
            Future<RsfListen> retry = endpoint.bind();
            EndpointFixture.Listen rejected = new EndpointFixture.Listen(endpoint, fixture.manager);
            operation.completed(rejected);
            assertFalse(rejected.isActive());
            assertFalse(retry.isDone());
            EndpointFixture.Listen listen = new EndpointFixture.Listen(endpoint, fixture.manager);
            endpoint.binding.completed(listen);
            assertSame(listen, retry.get());
        }
    }

    @Test
    public void failedBindCanRetryWithoutReinitializingConnector() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<RsfListen> result = endpoint.bind();
            IOException failure = new IOException("bind failed");
            endpoint.binding.failed(failure);
            assertSame(failure, result.getCause());
            endpoint.binding = null;
            assertTrue(endpoint.bind().get().isActive());
            assertEquals(1, endpoint.starts);
        }
    }

    @Test
    public void closeBindRejectsLateCompletionAndFurtherBinds() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<RsfListen> result = endpoint.bind();
            endpoint.closeBind();
            assertNotNull(result.getCause());
            EndpointFixture.Listen rejected = new EndpointFixture.Listen(endpoint, fixture.manager);
            endpoint.binding.completed(rejected);
            assertFalse(rejected.isActive());
            assertTrue(endpoint.getListenList().isEmpty());
            assertNotNull(ConnectorConnectionsTest.failure(fixture.manager.bind("tcp")));
            assertTrue(endpoint.connect(EndpointFixture.address("tcp")).get().isActive());
        }
    }

    @Test
    public void closeRejectsPendingBindAndReleasesTransportOnce() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<RsfListen> pending = endpoint.bind();
            endpoint.close();
            endpoint.close();
            assertNotNull(pending.getCause());
            assertEquals(1, fixture.events.stream().filter("tcp:closed"::equals).count());
        }
    }

    @Test
    public void completionCallbacksRunOutsideConnectorMonitor() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<RsfListen> result = endpoint.bind();
            AtomicBoolean held = new AtomicBoolean();
            result.onFinal(done -> held.set(Thread.holdsLock(endpoint)));
            endpoint.binding.completed(new EndpointFixture.Listen(endpoint, fixture.manager));
            result.get(1, TimeUnit.SECONDS);
            assertFalse(held.get());
        }
    }

    @Test
    public void failedCallbacksRunOutsideConnectorMonitorDuringClose() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<RsfListen> result = endpoint.bind();
            AtomicBoolean held = new AtomicBoolean();
            result.onFailed(done -> held.set(Thread.holdsLock(endpoint)));
            endpoint.closeBind();
            assertNotNull(result.getCause());
            assertFalse(held.get());
        }
    }

    @Test
    public void wrongListenerTypeIsRejectedAndClosed() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            EndpointFixture.Endpoint endpoint = this.endpoint(fixture);
            Future<RsfListen> result = endpoint.bind();
            AtomicBoolean closed = new AtomicBoolean();
            endpoint.binding.completed(new AbstractRsfListen("wrong", endpoint.config().address(), fixture.manager) {
                public boolean isActive() {
                    return !closed.get();
                }
                public void close() {
                    closed.set(true);
                }
            });
            assertNotNull(result.getCause());
            assertTrue(closed.get());
            assertTrue(endpoint.getListenList().isEmpty());
        }
    }

    @Test
    public void closedListenerCanBeBoundAgainButOnlyAtConfiguredAddress() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("tcp")) {
            RsfListen first = fixture.manager.bind("tcp").get();
            first.close();
            RsfListen second = fixture.manager.bind("tcp").get();
            assertNotSame(first, second);
            assertEquals(first.getBindAddress(), second.getBindAddress());
            assertEquals(1, fixture.endpoints.get("tcp").getListenList().size());
        }
    }
}
