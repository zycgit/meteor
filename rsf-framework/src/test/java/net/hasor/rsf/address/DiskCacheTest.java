/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.hasor.cobble.concurrent.timer.Timeout;
import net.hasor.cobble.concurrent.timer.Timer;
import net.hasor.cobble.concurrent.timer.TimerTask;
import org.junit.Test;
import static org.junit.Assert.*;
import net.hasor.rsf.address.route.FlowControlTest;

public class DiskCacheTest extends ScriptTestSupport {
    private final InterAddress a = new InterAddress("127.0.0.1", 8000, "zone");
    private final InterAddress b = new InterAddress("127.0.0.2", 8000, "zone");

    private DiskCache cache(AddressPool pool, File home) {
        return new DiskCache(pool, home, TimeUnit.HOURS.toMillis(1), TimeUnit.HOURS.toMillis(1));
    }

    private byte[] saveBucket(DiskCache cache, AddressBucket bucket) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(cache.saveToZip(bucket, bytes));
        return bytes.toByteArray();
    }

    private byte[] zipEntry(String name, String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(value.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    public void bucketZipRoundTripPreservesStaticAndDynamicAddresses() throws Exception {
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("s", this.a);
        pool.appendAddress("s", this.b);
        try (DiskCache cache = cache(pool, this.temporary.newFolder())) {
            byte[] bytes = saveBucket(cache, pool.getBucket("s"));
            AddressBucket restored = new AddressBucket("s", "zone");
            cache.readFromZip(restored, new ByteArrayInputStream(bytes));
            assertEquals(new HashSet<>(Arrays.asList(this.a, this.b)), new HashSet<>(restored.getAllAddresses()));
            assertEquals(Collections.singletonList(this.a), restored.getStaticAddresses());
            restored.invalidAddress(this.a, 60000);
            restored.invalidAddress(this.b, 60000);
            assertEquals(Collections.singletonList(this.a), restored.getAvailableAddresses());
        }
    }

    @Test
    public void bucketZipRoundTripPreservesFlowConfiguration() throws Exception {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", Arrays.asList(this.a, this.b));
        String config = "<controlSet>" + FlowControlTest.unit(true, 0.5, "192.0.2.*") + "</controlSet>";
        pool.updateFlowControl("s", config);
        try (DiskCache cache = cache(pool, this.temporary.newFolder())) {
            AddressBucket restored = new AddressBucket("s", "zone");
            cache.readFromZip(restored, new ByteArrayInputStream(saveBucket(cache, pool.getBucket("s"))));
            assertEquals(config, restored.getFlowControlRef().flowControlScript);
            assertTrue(restored.getFlowControlRef().unitFlowControl.enable());
        }
    }

    @Test
    public void serviceScriptSurvivesBucketZipRoundTrip() throws Exception {
        assertScriptRoundTrip(RouteTypeEnum.ServiceLevel);
    }

    @Test
    public void methodScriptSurvivesBucketZipRoundTrip() throws Exception {
        assertScriptRoundTrip(RouteTypeEnum.MethodLevel);
    }

    @Test
    public void argsScriptSurvivesBucketZipRoundTrip() throws Exception {
        assertScriptRoundTrip(RouteTypeEnum.ArgsLevel);
    }

    private void assertScriptRoundTrip(RouteTypeEnum type) throws Exception {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        String source = script("route-中文", (id, all) -> Collections.singletonList(this.a.getHostPort()));
        // Saving a bucket only needs a compiled script; routing return shapes are tested in RouteScriptTest.
        assertTrue(pool.getBucket("s").updateRoute(type, source));
        try (DiskCache cache = cache(pool, this.temporary.newFolder())) {
            AddressBucket restored = new AddressBucket("s", "zone");
            cache.readFromZip(restored, new ByteArrayInputStream(saveBucket(cache, pool.getBucket("s"))));
            InnerRuleEngine engine = type == RouteTypeEnum.ServiceLevel ? restored.getRuleRef().getServiceLevel() : type == RouteTypeEnum.MethodLevel ? restored.getRuleRef().getMethodLevel() : restored.getRuleRef().getArgsLevel();
            assertEquals(source, engine.getScript());
            assertTrue(engine.isEnable());
        }
    }

    @Test
    public void restoreReadsExternallyCreatedScriptEntries() throws Exception {
        String source = script("restore-script", (id, all) -> Collections.emptyList());
        AddressBucket bucket = new AddressBucket("s", "zone");
        try (DiskCache cache = cache(new AddressPool(), this.temporary.newFolder())) {
            for (String entry : Arrays.asList("service-level.groovy", "method-level.groovy", "args-level.groovy")) {
                cache.readFromZip(bucket, new ByteArrayInputStream(zipEntry(entry, source)));
            }
            assertEquals(source, bucket.getRuleRef().getServiceLevel().getScript());
            assertEquals(source, bucket.getRuleRef().getMethodLevel().getScript());
            assertEquals(source, bucket.getRuleRef().getArgsLevel().getScript());
        }
    }

    @Test
    public void fullSnapshotRestoresMultipleExistingServices() throws Exception {
        File home = this.temporary.newFolder();
        AddressPool source = new AddressPool();
        source.appendStaticAddress("s.one", this.a);
        source.appendAddress("s.two", this.b);
        try (DiskCache cache = cache(source, home)) {
            cache.storeConfig();
        }
        AddressPool restored = new AddressPool();
        restored.appendAddress("s.one", Collections.emptyList());
        restored.appendAddress("s.two", Collections.emptyList());
        try (DiskCache cache = cache(restored, home)) {
            cache.restoreConfig();
        }
        assertEquals(Collections.singletonList(this.a), restored.queryAllAddresses("s.one"));
        assertEquals(Collections.singletonList(this.b), restored.queryAllAddresses("s.two"));
        assertTrue(new File(home, "snapshot/address.index").isFile());
    }

    @Test
    public void restoredAddressesAreImmediatelySelectable() throws Exception {
        File home = this.temporary.newFolder();
        AddressPool source = new AddressPool();
        source.appendAddress("s", this.a);
        try (DiskCache cache = cache(source, home)) {
            cache.storeConfig();
        }
        AddressPool restored = new AddressPool();
        restored.appendAddress("s", Collections.emptyList());
        try (DiskCache cache = cache(restored, home)) {
            cache.restoreConfig();
        }
        assertEquals(Collections.singletonList(this.a), restored.queryAvailableAddresses("s"));
        assertEquals("restoreConfig must rebuild selection caches", this.a, restored.nextAddress("s", "get", null));
    }

    @Test
    public void restoreDoesNotResurrectUnregisteredService() throws Exception {
        File home = this.temporary.newFolder();
        AddressPool source = new AddressPool();
        source.appendAddress("s", this.a);
        try (DiskCache cache = cache(source, home)) {
            cache.storeConfig();
        }
        AddressPool restored = new AddressPool();
        try (DiskCache cache = cache(restored, home)) {
            cache.restoreConfig();
        }
        assertTrue(restored.getBucketNames().isEmpty());
    }

    @Test
    public void missingOrBrokenIndexDoesNotDestroyLiveAddresses() throws Exception {
        File home = this.temporary.newFolder();
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        try (DiskCache cache = cache(pool, home)) {
            cache.restoreConfig();
            File snapshot = new File(home, "snapshot");
            assertTrue(snapshot.mkdirs());
            File index = new File(snapshot, "address.index");
            Files.write(index.toPath(), new byte[0]);
            cache.restoreConfig();
            Files.write(index.toPath(), Collections.singletonList("absent.zip"), StandardCharsets.UTF_8);
            cache.restoreConfig();
            Files.write(new File(snapshot, "broken.zip").toPath(), new byte[] { 1, 2, 3 });
            Files.write(index.toPath(), Collections.singletonList("broken.zip"), StandardCharsets.UTF_8);
            cache.restoreConfig();
            assertEquals(this.a, pool.nextAddress("s", "get", null));
        }
    }

    @Test
    public void invalidAddressLineDoesNotDiscardOtherValidLines() throws Exception {
        AddressBucket bucket = new AddressBucket("s", "zone");
        String lines = "# comment\nD|" + this.a + "\nD|rsf://127.0.0.9:8000/\nD|" + this.b + "\n";
        try (DiskCache cache = cache(new AddressPool(), this.temporary.newFolder())) {
            cache.readFromZip(bucket, new ByteArrayInputStream(zipEntry("address.sal", lines)));
        }
        assertEquals(new HashSet<>(Arrays.asList(this.a, this.b)), new HashSet<>(bucket.getAllAddresses()));
    }

    @Test
    public void oldSnapshotsAreRemovedAndRecentOnesRetained() throws Exception {
        File home = this.temporary.newFolder();
        File snapshot = new File(home, "snapshot");
        assertTrue(snapshot.mkdirs());
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss");
        File old = new File(snapshot, "addr-pool-" + format.format(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(9))) + ".zip");
        File recent = new File(snapshot, "addr-pool-" + format.format(new Date()) + ".zip");
        assertTrue(old.createNewFile());
        assertTrue(recent.createNewFile());
        try (DiskCache cache = cache(new AddressPool(), home)) {
            cache.clearCacheData();
        }
        assertFalse("Retention must match names written by storeConfig", old.exists());
        assertTrue(recent.exists());
    }

    @Test
    public void scheduledMaintenanceReschedulesBothTasks() throws Exception {
        try (DiskCache cache = cache(new AddressPool(), this.temporary.newFolder())) {
            Field timerField = DiskCache.class.getDeclaredField("timer");
            timerField.setAccessible(true);
            Timer original = (Timer) timerField.get(cache);
            Set<Timeout> scheduled = original.stop();
            assertEquals(2, scheduled.size());
            RecordingTimer timer = new RecordingTimer();
            timerField.set(cache, timer);
            // Execute the real scheduled callbacks without waiting an hour.
            for (Timeout timeout : scheduled) {
                timeout.task().run(timeout);
            }
            assertEquals("Cleanup and persistence are periodic tasks", 2, timer.scheduled);
        }
    }

    @Test
    public void closeCancelsOutstandingMaintenance() throws Exception {
        DiskCache cache = cache(new AddressPool(), this.temporary.newFolder());
        cache.close();
        cache.close();
        Field timerField = DiskCache.class.getDeclaredField("timer");
        timerField.setAccessible(true);
        assertTrue(((Timer) timerField.get(cache)).stop().isEmpty());
    }

    @Test
    public void consecutiveSavesUseDifferentArchives() throws Exception {
        File home = this.temporary.newFolder();
        try (DiskCache cache = cache(new AddressPool(), home)) {
            cache.storeConfig();
            String first = new String(Files.readAllBytes(new File(home, "snapshot/address.index").toPath()), StandardCharsets.UTF_8);
            cache.storeConfig();
            String second = new String(Files.readAllBytes(new File(home, "snapshot/address.index").toPath()), StandardCharsets.UTF_8);
            assertNotEquals(first, second);
            assertTrue(new File(home, "snapshot/" + first).isFile());
            assertTrue(new File(home, "snapshot/" + second).isFile());
        }
    }

    @Test
    public void failedArchiveWritePreservesLastWorkingIndex() throws Exception {
        File home = this.temporary.newFolder();
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        try (DiskCache cache = cache(pool, home)) {
            cache.storeConfig();
        }
        byte[] index = Files.readAllBytes(new File(home, "snapshot/address.index").toPath());
        try (DiskCache failing = new DiskCache(pool, home, 3600000, 3600000) {
            @Override
            protected boolean saveToZip(AddressBucket bucket, OutputStream out) throws IOException {
                throw new IOException("Injected archive write failure");
            }
        }) {
            try {
                failing.storeConfig();
                fail("Write errors must reach the caller");
            } catch (IOException expected) {
                assertEquals("Injected archive write failure", expected.getMessage());
            }
        }
        assertArrayEquals(index, Files.readAllBytes(new File(home, "snapshot/address.index").toPath()));
        AddressPool restored = new AddressPool();
        restored.appendAddress("s", Collections.emptyList());
        try (DiskCache cache = cache(restored, home)) {
            cache.restoreConfig();
        }
        assertEquals(this.a, restored.nextAddress("s", "get", null));
    }

    @Test
    public void retentionPreservesIndexedRecoveryPointAndRemovesNewFilenameFormat() throws Exception {
        File home = this.temporary.newFolder();
        File snapshot = new File(home, "snapshot");
        assertTrue(snapshot.mkdirs());
        File indexed = new File(snapshot, "addr-pool-20000101-000000.zip");
        File obsolete = new File(snapshot, "addr-pool-20000101-000001-abc123.zip");
        assertTrue(indexed.createNewFile());
        assertTrue(obsolete.createNewFile());
        Files.write(new File(snapshot, "address.index").toPath(), Collections.singletonList(indexed.getName()), StandardCharsets.UTF_8);
        try (DiskCache cache = cache(new AddressPool(), home)) {
            cache.clearCacheData();
        }
        assertTrue(indexed.exists());
        assertFalse(obsolete.exists());
    }

    @Test
    public void maintenanceFailureStillSchedulesAnotherRun() throws Exception {
        try (DiskCache cache = new DiskCache(new AddressPool(), this.temporary.newFolder(), 3600000, 3600000) {
            @Override
            public synchronized void storeConfig() throws IOException {
                throw new IOException("test");
            }

            @Override
            protected void clearCacheData() {
                throw new IllegalStateException("test");
            }
        }) {
            Field field = DiskCache.class.getDeclaredField("timer");
            field.setAccessible(true);
            Set<Timeout> callbacks = ((Timer) field.get(cache)).stop();
            RecordingTimer timer = new RecordingTimer();
            field.set(cache, timer);
            for (Timeout callback : callbacks) {
                callback.task().run(callback);
            }
            assertEquals(2, timer.scheduled);
            cache.close();
            for (Timeout callback : callbacks) {
                callback.task().run(callback);
            }
            assertEquals("Closed callbacks must not restart maintenance", 2, timer.scheduled);
        }
    }

    private static class RecordingTimer implements Timer {
        int scheduled;

        public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
            this.scheduled++;
            return null;
        }

        public Set<Timeout> stop() {
            return Collections.emptySet();
        }
    }
}
