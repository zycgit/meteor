/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.Arrays;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OptionInfoTest {
    @Test
    public void nullKeysAndValuesRemainPresentUntilRemoved() {
        OptionInfo options = new OptionInfo();
        options.addOption(null, "value");
        options.addOption("nullable", null);
        assertEquals("value", options.getOption(null));
        assertTrue(Arrays.asList(options.getOptionKeys()).contains("nullable"));

        // 复制保留键存在性，后续修改只影响副本。
        options.addOption(null, null);
        OptionInfo copy = new OptionInfo();
        copy.addOptionMap(options);
        assertEquals(2, copy.getOptionKeys().length);
        assertTrue(Arrays.asList(copy.getOptionKeys()).contains(null));
        assertNull(copy.getOption(null));
        assertNull(copy.getOption("nullable"));

        copy.addOption("nullable", "updated");
        assertEquals("updated", copy.getOption("nullable"));
        assertNull(options.getOption("nullable"));
        copy.removeOption(null);
        copy.removeOption("nullable");
        assertEquals(0, copy.getOptionKeys().length);
        assertNull(copy.getOption(null));
    }

    @Test
    public void concurrentWritersRetainIndependentUpdatesAndFinalValues() throws Exception {
        OptionInfo options = new OptionInfo();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?>[] writes = new Future<?>[2];
            for (int writer = 0; writer < writes.length; writer++) {
                String prefix = "writer-" + writer + "-";
                writes[writer] = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int i = 0; i < 1000; i++) {
                        String key = prefix + i;
                        options.addOption(key, "initial");
                        options.addOption(key, "final");
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> write : writes) {
                write.get(10, TimeUnit.SECONDS);
            }

            // 写入结束后，所有独立键都应保留，并能复制到最终值。
            OptionInfo copy = new OptionInfo();
            copy.addOptionMap(options);
            assertEquals(2000, copy.getOptionKeys().length);
            for (int writer = 0; writer < writes.length; writer++) {
                for (int i = 0; i < 1000; i++) {
                    assertEquals("final", copy.getOption("writer-" + writer + "-" + i));
                }
            }
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void sharedDefaultsSupportConcurrentMutationAndRequestCopiesIncludingNullValues() throws Exception {
        OptionInfo defaults = new OptionInfo();
        defaults.addOption("nullable", null);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> writer = workers.submit(() -> {
                for (int i = 0; i < 10000; i++) {
                    defaults.addOption("key-" + (i % 32), Integer.toString(i));
                    defaults.removeOption("key-" + ((i + 1) % 32));
                }
            });
            Runnable copy = () -> {
                for (int i = 0; i < 3000; i++) {
                    OptionInfo request = new OptionInfo();
                    request.addOptionMap(defaults);
                    assertTrue(Arrays.asList(request.getOptionKeys()).contains("nullable"));
                    assertNull(request.getOption("nullable"));
                    request.addOption("request-only", "value");
                }
            };
            Future<?> first = workers.submit(copy);
            Future<?> second = workers.submit(copy);
            writer.get(10, TimeUnit.SECONDS);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertNull(defaults.getOption("request-only"));
            defaults.addOption("final", "value");
            assertEquals("value", defaults.getOption("final"));
        } finally {
            workers.shutdownNow();
        }
    }
}
