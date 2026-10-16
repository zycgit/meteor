/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.transport.http;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.meteor.connector.RouteTestAccess;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.http1.Http1UpgradeTestAccess;
import org.junit.Test;
import static org.junit.Assert.*;

/** HTTP upgrade authorization ordering at the Neta handshake boundary. */
public class HttpUpgradeAuthorizationTest {
    @Test(timeout = 10000)
    public void http1AuthorizationCompletesAfterTheReceivePipelineReleasesItsChannelLock() throws Exception {
        // Hold the simulated Neta receive pipeline after its authorizer returns.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RouteMountIndex paths = RouteTestAccess.mountIndex(Map.of("contextPath", "/ws"));
        Map<String, String> options = Map.of("upgrade", "websocket");
        RouteSelection expected = RouteTestAccess.selection("jsonws", paths.get("/ws"), options);
        AtomicReference<RouteSelection> selected = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch releaseReceive = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        Thread protocol = new Thread(() -> {
            try {
                Http1UpgradeTestAccess.authorize(executor, uri -> expected, "/ws", route -> {
                    selected.set(route);
                    completed.countDown();
                }, returned, releaseReceive);
            } catch (Throwable caught) {
                failure.set(caught);
                completed.countDown();
            }
        }, "neta-protocol-test");
        try {
            protocol.start();
            assertTrue(returned.await(3, TimeUnit.SECONDS));

            // The worker may select the route, but it cannot complete the callback inside the receive pipeline.
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS));
            releaseReceive.countDown();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            protocol.join(3000);

            // Completion observes the selected route after the protocol thread releases the channel monitor.
            assertNull(failure.get());
            assertSame(expected, selected.get());
        } finally {
            releaseReceive.countDown();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void http1AuthorizationWaitsForAnEarlierResponseToFinish() throws Exception {
        // Register an ordinary HTTP request before asking the same connection to upgrade.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RouteMountIndex paths = RouteTestAccess.mountIndex(Map.of("contextPath", "/ws"));
        Map<String, String> options = Map.of("upgrade", "websocket");
        RouteSelection expected = RouteTestAccess.selection("jsonws", paths.get("/ws"), options);
        Http1UpgradeTestAccess.PendingAuthorization fixture = //
                Http1UpgradeTestAccess.pending(executor, uri -> expected);
        try {
            HttpExchange ordinary = fixture.request().get(3, TimeUnit.SECONDS);
            AtomicReference<RouteSelection> selected = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            fixture.authorize("/ws", route -> {
                selected.set(route);
                completed.countDown();
            });

            // The WebSocket result cannot overtake the response already registered on this HTTP/1 connection.
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS));
            fixture.respond(ordinary).get(3, TimeUnit.SECONDS);
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertSame(expected, selected.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
