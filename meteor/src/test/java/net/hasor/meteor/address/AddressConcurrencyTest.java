/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AddressConcurrencyTest {
    @Rule
    public final  TemporaryFolder directory = new TemporaryFolder();
    private final InterAddress    first     = new InterAddress("127.0.0.1", 8000, "unit");
    private final InterAddress    second    = new InterAddress("127.0.0.2", 8000, "unit");

    @Test
    public void slowBucketOutputDoesNotBlockAddressChanges() throws Exception {
        // 在输出第一批压缩字节时暂停，不通过内部锁或反射控制被测对象。
        AddressBucket bucket = new AddressBucket("service", "unit");
        bucket.newAddress(List.of(this.first), AddressTypeEnum.Static);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream output = new OutputStream() {
            private boolean paused;

            @Override
            public void write(int value) {
                if (!this.paused) {
                    this.paused = true;
                    writing.countDown();
                    await(release);
                }
                bytes.write(value);
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (DiskCache cache = new DiskCache(new AddressPool(), this.directory.newFolder(), 60000, 3600000)) {
            Future<?> saving = workers.submit(() -> cache.saveToZip(bucket, output));
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS));

                // 文件输出未完成，同一服务仍能更新和读取地址。
                Future<?> changing = workers.submit(() -> {
                    bucket.newAddress(List.of(this.second), AddressTypeEnum.Dynamic);
                });
                changing.get(5, TimeUnit.SECONDS);
                assertEquals(List.of(this.first, this.second), bucket.getAllAddresses());
            } finally {
                release.countDown();
            }
            saving.get(5, TimeUnit.SECONDS);

            // 归档保持采样时的内容，后续更新不会混入已经开始的快照。
            AddressBucket restored = new AddressBucket("service", "unit");
            cache.readFromZip(restored, new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(List.of(this.first), restored.getAllAddresses());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void savingArchiveDoesNotBlockOtherServiceRegistration() throws Exception {
        AddressPool pool = new AddressPool("unit", 30000);
        pool.appendAddress("first", this.first);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (DiskCache cache = new DiskCache(pool, this.directory.newFolder(), 60000, 3600000) {
            @Override
            protected boolean saveToZip(AddressBucket bucket, OutputStream output) throws IOException {
                writing.countDown();
                await(release);
                return super.saveToZip(bucket, output);
            }
        }) {
            Future<?> saving = workers.submit(() -> {
                cache.storeConfig();
                return null;
            });
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                workers.submit(() -> pool.appendAddress("second", this.second)).get(5, TimeUnit.SECONDS);
                assertEquals(this.second, pool.nextAddress("second", "echo", null));
            } finally {
                release.countDown();
            }
            saving.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void slowAddressNotificationDoesNotBlockAnotherService() throws Exception {
        AddressPool pool = new AddressPool("unit", 30000);
        pool.appendAddress("first", this.first);
        CountDownLatch notifying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean notified = new AtomicBoolean();
        pool.getBucket("first").addObserver((source, value) -> {
            if (notified.compareAndSet(false, true)) {
                notifying.countDown();
                await(release);
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> updating = workers.submit(() -> pool.refreshAddress("first", List.of(this.second)));
            try {
                assertTrue(notifying.await(5, TimeUnit.SECONDS));

                // 更新回调仍然挂起，另一服务可以新增、选址并移除。
                workers.submit(() -> pool.appendAddress("second", this.first)).get(5, TimeUnit.SECONDS);
                assertEquals(this.first, pool.nextAddress("second", "echo", null));
                assertTrue(workers.submit(() -> pool.removeBucket("second")).get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            updating.get(5, TimeUnit.SECONDS);
            assertEquals(this.second, pool.nextAddress("first", "echo", null));
            assertNull(pool.nextAddress("second", "echo", null));
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void concurrentRouteUpdatesPreserveOtherLevels() throws Exception {
        AddressBucket bucket = new AddressBucket("service", "unit");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 30; i++) {
                String service = "<route><!-- service " + i + " --><return value='addresses'/></route>";
                String method = "<route><!-- method " + i + " --><return value='addresses'/></route>";
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Boolean> a = workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return bucket.updateRoute(RouteTypeEnum.ServiceLevel, service);
                });
                Future<Boolean> b = workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return bucket.updateRoute(RouteTypeEnum.MethodLevel, method);
                });
                assertTrue(a.get(5, TimeUnit.SECONDS));
                assertTrue(b.get(5, TimeUnit.SECONDS));
                assertEquals(service, bucket.getRuleRef().getServiceLevel().getScript());
                assertEquals(method, bucket.getRuleRef().getMethodLevel().getScript());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("Concurrent operation was not released", latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
