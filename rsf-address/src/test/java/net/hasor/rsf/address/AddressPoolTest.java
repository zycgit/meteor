package net.hasor.rsf.address;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
        pool.removeAddress("missing", a);
        pool.refreshAddress("missing", Collections.singletonList(a));
        assertTrue(pool.getBucketNames().isEmpty());
    }

    @Test
    public void invalidationAndAppendReactivateDynamicAddress() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", a);
        assertEquals(a, pool.nextAddress("s", "get", null));
        pool.invalidAddress(a);
        assertNull(pool.nextAddress("s", "get", null));
        assertEquals(Collections.singletonList(a), pool.queryInvalidAddresses("s"));
        pool.appendAddress("s", a);
        assertEquals(a, pool.nextAddress("s", "get", null));
        assertTrue(pool.queryInvalidAddresses("s").isEmpty());
        assertEquals(1, pool.queryAllAddresses("s").size());
    }

    @Test
    public void staticAddressIgnoresInvalidationButCanBeExplicitlyRemoved() {
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("s", a);
        pool.invalidAddress(a);
        assertEquals(a, pool.nextAddress("s", "get", null));
        pool.removeAddress("s", a);
        assertNull(pool.nextAddress("s", "get", null));
        assertTrue(pool.getBucket("s").getStaticAddresses().isEmpty());
    }

    @Test
    public void globalRemovalAndBucketRemovalInvalidateSelectionCache() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("one", Arrays.asList(a, b));
        pool.appendAddress("two", a);
        pool.removeAddress(a);
        assertEquals(b, pool.nextAddress("one", "get", null));
        assertNull(pool.nextAddress("two", "get", null));
        assertTrue(pool.removeBucket("one"));
        assertFalse(pool.removeBucket("one"));
        assertNull(pool.nextAddress("one", "get", null));
    }

    @Test
    public void replaceDynamicAddressesUpdatesCachedSelection() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", a);
        pool.refreshAddress("s", Collections.singletonList(b));
        assertEquals(Collections.singletonList(b), pool.queryAllAddresses("s"));
        assertEquals(b, pool.nextAddress("s", "get", null));
    }

    @Test
    public void emptyRegistryRefreshRemovesLastDynamicProvider() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", a);
        pool.refreshAddress("s", Collections.emptyList());
        assertNull("An empty provider list must withdraw the old provider", pool.nextAddress("s", "get", null));
    }

    @Test
    public void registryRefreshPreservesConfiguredStaticAddress() {
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("s", a);
        pool.refreshAddress("s", Collections.singletonList(b));
        assertTrue("Registry refresh must preserve static providers", pool.queryAllAddresses("s").contains(a));
    }

    @Test
    public void queryListsAreReadOnlyAndBucketNamesAreDetached() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", a);
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
        assertEquals(Collections.singletonList(a), pool.queryAllAddresses("s"));
    }

    @Test
    public void unitSnapshotCannotMutateLiveAddressSelection() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", a);
        pool.allServiceAddressToSnapshot().get("s_UNIT").clear();
        assertEquals("A caller-owned snapshot must not empty the live routing cache", a, pool.nextAddress("s", "get", null));
    }

    @Test
    public void selectionRetriesQuotaCheckUntilAccepted() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", a);
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        pool.getBucket("s").getFlowControlRef().speedFlowControl = new net.hasor.rsf.address.route.speed.SpeedFlowControl() {
            @Override
            public boolean callCheck(String service, String method, InterAddress address) {
                assertEquals("s", service);
                assertEquals("get", method);
                assertEquals(a, address);
                return checks.incrementAndGet() >= 3;
            }
        };
        assertEquals(a, pool.nextAddress("s", "get", null));
        assertEquals(3, checks.get());
    }

    @Test
    public void interruptedSelectionCanBeCancelledWithoutClearingInterrupt() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", a);
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
        pool.appendAddress("s", a);
        pool.getBucket("s").getFlowControlRef().speedFlowControl = new net.hasor.rsf.address.route.speed.SpeedFlowControl() {
            @Override
            public boolean callCheck(String service, String method, InterAddress address) {
                pool.removeAddress("s", a);
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
        pool.appendAddress(service, a);
        pool.updateServiceRoute(service, script("blocking-route", route));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            route.pause.set(true);
            Future<?> first = executor.submit(() -> pool.appendAddress("first", a));
            assertTrue(route.captured.await(5, TimeUnit.SECONDS));
            pool.appendAddress("second", b);
            route.resume.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(a, pool.nextAddress("first", "get", null));
            assertEquals(b, pool.nextAddress("second", "get", null));
        } finally {
            route.resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void olderCacheResetCannotOverwriteNewerProviderList() throws Exception {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", a);
        BlockingRoute route = new BlockingRoute();
        String service = pool.getBucket("s") == null ? "first" : "s";
        pool.appendAddress(service, a);
        pool.updateServiceRoute(service, script("blocking-route", route));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            route.pause.set(true);
            Future<?> olderReset = executor.submit(() -> pool.appendAddress("s", a));
            assertTrue(route.captured.await(5, TimeUnit.SECONDS));
            pool.refreshAddress("s", Collections.singletonList(b));
            assertEquals(b, pool.nextAddress("s", "get", null));
            route.resume.countDown();
            olderReset.get(5, TimeUnit.SECONDS);
            assertEquals("After both writers finish, routing must use the latest provider list", b, pool.nextAddress("s", "get", null));
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
            if (pause.compareAndSet(true, false)) {
                captured.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) {
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
