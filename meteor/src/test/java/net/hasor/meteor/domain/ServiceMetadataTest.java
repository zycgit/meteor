/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import org.junit.Test;
import static org.junit.Assert.*;

public class ServiceMetadataTest {
    @Test
    public void singleAndBulkWritesShareLimitAndRejectedBatchChangesNothing() {
        MetBindInfo<Runnable> service = new MetBindInfoWrap<>(new ServiceDomain<>(Runnable.class));
        Map<String, Object> values = entries(1000);
        service.setMetaData(values);
        service.setMetaData("k0", "updated");
        assertEquals("updated", service.getMetaData("k0"));
        assertThrows(IllegalArgumentException.class, () -> service.setMetaData("extra", 1));
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("k0", "should not change");
        rejected.put("extra", 1);
        assertThrows(IllegalArgumentException.class, () -> service.setMetaData(rejected));
        assertEquals("updated", service.getMetaData("k0"));
        assertNull(service.getMetaData("extra"));
        service.removeMetaData("k1");
        service.setMetaData("extra", 1);
        assertEquals(1, service.getMetaData("extra"));
        service.setMetaData("extra", null);
        service.setMetaData(Map.of("new", 2));
        assertEquals(2, service.getMetaData("new"));
    }

    @Test
    public void batchCanSwapKeysAtCapacityRegardlessOfEntryOrder() {
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setMetaData(entries(1000));
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("replacement", true);
        changes.put("k0", null);
        service.setMetaData(changes);
        assertEquals(true, service.getMetaData("replacement"));
        assertNull(service.getMetaData("k0"));
        assertEquals(999, service.getMetaData("k999"));
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("k1", "should not change");
        invalid.put(null, 1);
        assertThrows(NullPointerException.class, () -> service.setMetaData(invalid));
        assertEquals(1, service.getMetaData("k1"));
        assertThrows(IllegalArgumentException.class, () -> new ServiceDomain<>(Runnable.class).setMetaData(entries(1001)));
    }

    @Test
    public void concurrentWritersKeepAcceptedUpdatesWithBestEffortCapacity() throws Exception {
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setMetaData(entries(999));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String key = "concurrent" + i;
                calls.add(executor.submit(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        service.setMetaData(Map.of(key, true));
                        accepted.incrementAndGet();
                    } catch (IllegalArgumentException expected) {
                        assertTrue(expected.getMessage().contains("1000"));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }));
            }
            start.countDown();
            for (Future<?> call : calls) {
                call.get(5, TimeUnit.SECONDS);
            }
            assertTrue(accepted.get() >= 1);
            int stored = 0;
            for (int i = 0; i < 8; i++) {
                if (Boolean.TRUE.equals(service.getMetaData("concurrent" + i))) {
                    stored++;
                }
            }
            assertEquals(accepted.get(), stored);
            assertThrows(IllegalArgumentException.class, () -> service.setMetaData("after-writes", true));
            service.setMetaData("k0", "updated");
            assertEquals("updated", service.getMetaData("k0"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void concurrentSingleAndBulkWritesPreserveUnrelatedKeys() throws Exception {
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String prefix = "writer-" + i + "-";
                calls.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int item = 0; item < 50; item++) {
                        service.setMetaData(prefix + "single-" + item, item);
                        service.setMetaData(Map.of(prefix + "batch-" + item, item));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> call : calls) {
                call.get(10, TimeUnit.SECONDS);
            }

            for (int writer = 0; writer < 8; writer++) {
                String prefix = "writer-" + writer + "-";
                for (int item = 0; item < 50; item++) {
                    assertEquals(item, service.getMetaData(prefix + "single-" + item));
                    assertEquals(item, service.getMetaData(prefix + "batch-" + item));
                }
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static Map<String, Object> entries(int size) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            values.put("k" + i, i);
        }
        return values;
    }
}
