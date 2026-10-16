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
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
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
                // 首个输出字节前暂停，冻结已采样快照与后续地址变更的先后关系。
                if (!this.paused) {
                    this.paused = true;
                    writing.countDown();
                    await(release);
                }
                bytes.write(value);
            }
        };
        File home = this.directory.newFolder();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (DiskCache cache = new DiskCache(new AddressPool(), home, 60000, 3600000)) {
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
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void savingArchiveDoesNotBlockOtherServiceRegistration() throws Exception {
        // 为一个服务准备地址，并用门闩暂停该服务的归档写入。
        AddressPool pool = new AddressPool("unit", 30000);
        pool.appendAddress("first", this.first);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        File home = this.directory.newFolder();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (DiskCache cache = new DiskCache(pool, home, 60000, 3600000) {
            @Override
            public boolean saveToZip(AddressBucket bucket, OutputStream output) throws IOException {
                // 只拦截写入时机，实际压缩仍委托生产实现。
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
                // 一个服务仍在写盘时，另一个服务可以完成注册和选址。
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                Future<?> registration = workers.submit(() -> pool.appendAddress("second", this.second));
                registration.get(5, TimeUnit.SECONDS);
                assertEquals(this.second, pool.nextAddress("second", "echo", null));
            } finally {
                release.countDown();
            }
            saving.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void slowAddressNotificationDoesNotBlockAnotherService() throws Exception {
        // 首次地址更新回调暂停，显式控制两个服务操作的交错。
        AddressPool pool = new AddressPool("unit", 30000);
        pool.appendAddress("first", this.first);
        CountDownLatch notifying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean notified = new AtomicBoolean();
        pool.getBucket("first").addObserver((source, value) -> {
            // 只阻塞首次通知，避免后续释放流程再次进入等待。
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
                Future<?> registration = workers.submit(() -> pool.appendAddress("second", this.first));
                registration.get(5, TimeUnit.SECONDS);
                assertEquals(this.first, pool.nextAddress("second", "echo", null));
                Future<Boolean> removal = workers.submit(() -> pool.removeBucket("second"));
                assertTrue(removal.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            // 释放回调后，原服务完成更新，另一服务保持已删除状态。
            updating.get(5, TimeUnit.SECONDS);
            assertEquals(this.second, pool.nextAddress("first", "echo", null));
            assertNull(pool.nextAddress("second", "echo", null));
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void concurrentRouteUpdatesPreserveOtherLevels() throws Exception {
        // 同一地址桶的两个路由层级从同一个屏障并发更新。
        AddressPool pool = new AddressPool("unit", 30000);
        pool.appendAddress("service", Collections.emptyList());
        AddressBucket bucket = pool.getBucket("service");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            // 多轮只增加同一并发场景的交错机会，不混合其他路由行为。
            for (int i = 0; i < 30; i++) {
                String service = "<route><!-- service " + i + " --><return value='addresses'/></route>";
                String method = "<route><!-- method " + i + " --><return value='addresses'/></route>";
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Boolean> serviceUpdate = workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return bucket.updateRoute(RouteTypeEnum.ServiceLevel, service);
                });
                Future<Boolean> methodUpdate = workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return bucket.updateRoute(RouteTypeEnum.MethodLevel, method);
                });
                // 两个更新都成功，公开查询必须同时保留两个层级的结果。
                assertTrue(serviceUpdate.get(5, TimeUnit.SECONDS));
                assertTrue(methodUpdate.get(5, TimeUnit.SECONDS));
                assertEquals(service, pool.serviceRoute("service"));
                assertEquals(method, pool.methodRoute("service"));
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
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
