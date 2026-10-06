/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.address.route.speed.SpeedFlowControl;
import org.junit.Test;
import static org.junit.Assert.*;

public class AddressPoolTest extends ScriptTestSupport {
    private final InterAddress a = new InterAddress("127.0.0.1", 8000, "local");
    private final InterAddress b = new InterAddress("127.0.0.2", 8000, "remote");

    @Test
    public void missingServiceHasNoAddressOrConfiguration() {
        AddressPool pool = new AddressPool();
        assertNull(pool.nextAddress("missing", "get", null));
        assertNull(pool.queryAllAddresses("missing"));
        assertNull(pool.queryAvailableAddresses("missing"));
        assertNull(pool.queryInvalidAddresses("missing"));
        assertNull(pool.queryLocalUnitAddresses("missing"));
        assertNull(pool.serviceRoute("missing"));
        assertNull(pool.methodRoute("missing"));
        assertNull(pool.argsRoute("missing"));
        assertNull(pool.flowControl("missing"));
        assertFalse(pool.removeBucket("missing"));
        assertFalse(pool.updateServiceRoute("missing", "script"));
        assertFalse(pool.updateFlowControl("missing", "<controlSet></controlSet>"));
        pool.removeAddress("missing", this.a);
        pool.refreshAddress("missing", Collections.singletonList(this.a));
        assertTrue(pool.getBucketNames().isEmpty());
    }

    @Test
    public void invalidationAndAppendReactivateDynamicAddress() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        assertEquals(this.a, pool.nextAddress("s", "get", null));
        pool.invalidAddress(this.a);
        assertNull(pool.nextAddress("s", "get", null));
        assertEquals(Collections.singletonList(this.a), pool.queryInvalidAddresses("s"));
        pool.appendAddress("s", this.a);
        assertEquals(this.a, pool.nextAddress("s", "get", null));
        assertTrue(pool.queryInvalidAddresses("s").isEmpty());
        assertEquals(1, pool.queryAllAddresses("s").size());
    }

    @Test
    public void staticAddressIgnoresInvalidationButCanBeExplicitlyRemoved() {
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("s", this.a);
        pool.invalidAddress(this.a);
        assertEquals(this.a, pool.nextAddress("s", "get", null));
        pool.removeAddress("s", this.a);
        assertNull(pool.nextAddress("s", "get", null));
        assertTrue(pool.getBucket("s").getStaticAddresses().isEmpty());
    }

    @Test
    public void globalRemovalAndBucketRemovalInvalidateSelectionCache() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("one", Arrays.asList(this.a, this.b));
        pool.appendAddress("two", this.a);
        pool.removeAddress(this.a);
        assertEquals(this.b, pool.nextAddress("one", "get", null));
        assertNull(pool.nextAddress("two", "get", null));
        assertTrue(pool.removeBucket("one"));
        assertFalse(pool.removeBucket("one"));
        assertNull(pool.nextAddress("one", "get", null));
    }

    @Test
    public void replaceDynamicAddressesUpdatesCachedSelection() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        pool.refreshAddress("s", Collections.singletonList(this.b));
        assertEquals(Collections.singletonList(this.b), pool.queryAllAddresses("s"));
        assertEquals(this.b, pool.nextAddress("s", "get", null));
    }

    @Test
    public void emptyRegistryRefreshRemovesLastDynamicProvider() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        pool.refreshAddress("s", Collections.emptyList());
        assertNull("An empty provider list must withdraw the old provider", pool.nextAddress("s", "get", null));
    }

    @Test
    public void registryRefreshPreservesConfiguredStaticAddress() {
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("s", this.a);
        pool.refreshAddress("s", Collections.singletonList(this.b));
        assertTrue("Registry refresh must preserve static providers", pool.queryAllAddresses("s").contains(this.a));
    }

    @Test
    public void queryListsAreReadOnlyAndBucketNamesAreDetached() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        try {
            pool.queryAvailableAddresses("s").clear();
            fail("Query lists must be read-only");
        } catch (UnsupportedOperationException expected) {
            // Public query contract.
        }
        pool.getBucketNames().clear();
        assertNotNull(pool.getBucket("s"));
        Map<String, List<InterAddress>> snapshot = pool.allServiceAddressToSnapshot();
        snapshot.get("s_ALL").clear();
        assertEquals(Collections.singletonList(this.a), pool.queryAllAddresses("s"));
    }

    @Test
    public void unitSnapshotCannotMutateLiveAddressSelection() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        pool.allServiceAddressToSnapshot().get("s_UNIT").clear();
        assertEquals("A caller-owned snapshot must not empty the live routing cache", this.a, pool.nextAddress("s", "get", null));
    }

    @Test
    public void selectionRetriesQuotaCheckUntilAccepted() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        AtomicInteger checks = new AtomicInteger();
        pool.getBucket("s").getFlowControlRef().speedFlowControl = new SpeedFlowControl() {
            @Override
            public boolean callCheck(String service, String method, InterAddress address) {
                assertEquals("s", service);
                assertEquals("get", method);
                assertEquals(AddressPoolTest.this.a, address);
                return checks.incrementAndGet() >= 3;
            }
        };
        assertEquals(this.a, pool.nextAddress("s", "get", null));
        assertEquals(3, checks.get());
    }

    @Test
    public void interruptedSelectionCanBeCancelledWithoutClearingInterrupt() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        Thread.currentThread().interrupt();
        try {
            pool.nextAddress("s", "get", null);
            fail("Interrupted selection must stop");
        } catch (CancellationException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void waitingForQuotaObservesProviderWithdrawal() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        pool.getBucket("s").getFlowControlRef().speedFlowControl = new SpeedFlowControl() {
            @Override
            public boolean callCheck(String service, String method, InterAddress address) {
                pool.removeAddress("s", AddressPoolTest.this.a);
                return false;
            }
        };
        assertNull(pool.nextAddress("s", "get", null));
    }

    @Test
    public void concurrentServiceAdditionDuringCacheResetDoesNotThrow() throws Exception {
        AddressPool pool = new AddressPool();
        BlockingRoute route = new BlockingRoute();
        String service = pool.getBucket("s") == null ? "first" : "s";
        pool.appendAddress(service, this.a);
        pool.updateServiceRoute(service, script("blocking-route", route));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            route.pause.set(true);
            Future<?> first = executor.submit(() -> pool.appendAddress("first", this.a));
            assertTrue(route.captured.await(5, TimeUnit.SECONDS));
            pool.appendAddress("second", this.b);
            route.resume.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(this.a, pool.nextAddress("first", "get", null));
            assertEquals(this.b, pool.nextAddress("second", "get", null));
        } finally {
            route.resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void olderCacheResetCannotOverwriteNewerProviderList() throws Exception {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        BlockingRoute route = new BlockingRoute();
        String service = pool.getBucket("s") == null ? "first" : "s";
        pool.appendAddress(service, this.a);
        pool.updateServiceRoute(service, script("blocking-route", route));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            route.pause.set(true);
            Future<?> olderReset = executor.submit(() -> pool.appendAddress("s", this.a));
            assertTrue(route.captured.await(5, TimeUnit.SECONDS));
            pool.refreshAddress("s", Collections.singletonList(this.b));
            assertEquals(this.b, pool.nextAddress("s", "get", null));
            route.resume.countDown();
            olderReset.get(5, TimeUnit.SECONDS);
            assertEquals("After both writers finish, routing must use the latest provider list", this.b, pool.nextAddress("s", "get", null));
        } finally {
            route.resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static class BlockingRoute implements RuleScript<List<String>> {
        final AtomicBoolean  pause    = new AtomicBoolean();
        final CountDownLatch captured = new CountDownLatch(1);
        final CountDownLatch resume   = new CountDownLatch(1);

        @Override
        public List<String> evalAddress(String service, List<String> addresses) {
            if (this.pause.compareAndSet(true, false)) {
                this.captured.countDown();
                try {
                    if (!this.resume.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting for concurrent update");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return addresses;
        }
    }
}
