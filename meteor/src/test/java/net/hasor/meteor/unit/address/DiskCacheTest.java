/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.address;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.hasor.meteor.address.AddressBucket;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.DiskCache;
import net.hasor.meteor.address.DiskCacheTestAccess;
import net.hasor.meteor.address.FlowControlRef;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.RouteTypeEnum;
import net.hasor.meteor.unit.address.route.FlowControlScripts;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DiskCacheTest {
    private static final long CACHE_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);

    @Rule
    public final  TemporaryFolder temporary = new TemporaryFolder();
    private final InterAddress    a         = new InterAddress("127.0.0.1", 8000, "zone");
    private final InterAddress    b         = new InterAddress("127.0.0.2", 8000, "zone");

    @Test
    public void bucketZipRoundTripPreservesStaticAndDynamicAddresses() throws Exception {
        // 在同一服务内准备静态与动态地址，验证归档同时保留地址及其类型。
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("s", this.a);
        pool.appendAddress("s", this.b);
        File home = this.temporary.newFolder();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS); //
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertTrue(cache.saveToZip(pool.getBucket("s"), bytes));

            // 恢复到新的地址桶，避免读到原来的内存地址。
            AddressBucket restored = new AddressBucket("s", "zone");
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray())) {
                cache.readFromZip(restored, input);
            }
            Set<InterAddress> expectedAddresses = new HashSet<>(Arrays.asList(this.a, this.b));
            Set<InterAddress> restoredAddresses = new HashSet<>(restored.getAllAddresses());
            assertEquals(expectedAddresses, restoredAddresses);
            assertEquals(Collections.singletonList(this.a), restored.getStaticAddresses());
        }
    }

    @Test
    public void restoredStaticAddressRemainsAvailableAfterInvalidation() throws Exception {
        // 先保存包含静态地址的服务桶。
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("s", this.a);
        File home = this.temporary.newFolder();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS); //
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertTrue(cache.saveToZip(pool.getBucket("s"), bytes));
            AddressBucket restored = new AddressBucket("s", "zone");
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray())) {
                cache.readFromZip(restored, input);
            }

            // 静态属性恢复后，失效标记仍不能排除该地址。
            restored.invalidAddress(this.a, TimeUnit.MINUTES.toMillis(1));
            assertEquals(Collections.singletonList(this.a), restored.getAvailableAddresses());
        }
    }

    @Test
    public void restoredDynamicAddressCanBeInvalidated() throws Exception {
        // 先保存包含动态地址的服务桶。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.b);
        File home = this.temporary.newFolder();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS); //
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertTrue(cache.saveToZip(pool.getBucket("s"), bytes));
            AddressBucket restored = new AddressBucket("s", "zone");
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray())) {
                cache.readFromZip(restored, input);
            }

            // 动态属性恢复后，失效标记会从可用列表中排除该地址。
            assertEquals(Collections.singletonList(this.b), restored.getAvailableAddresses());
            restored.invalidAddress(this.b, TimeUnit.MINUTES.toMillis(1));
            assertTrue(restored.getAvailableAddresses().isEmpty());
        }
    }

    @Test
    public void bucketZipRoundTripPreservesFlowConfiguration() throws Exception {
        // 准备已启用的单元流控规则。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", Arrays.asList(this.a, this.b));
        String config = "<controlSet>" + FlowControlScripts.unit(true, 0.5, "192.0.2.*") + "</controlSet>";
        pool.updateFlowControl("s", config);
        File home = this.temporary.newFolder();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS); //
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertTrue(cache.saveToZip(pool.getBucket("s"), bytes));

            // 在新的地址桶中恢复，检查配置文本及编译后的启用状态。
            AddressBucket restored = new AddressBucket("s", "zone");
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray())) {
                cache.readFromZip(restored, input);
            }
            FlowControlRef flowControl = restored.getFlowControlRef();
            assertEquals(config, flowControl.flowControlScript);
            assertTrue(flowControl.unitFlowControl.enable());
        }
    }

    @Test
    public void serviceScriptSurvivesBucketZipRoundTrip() throws Exception {
        // 服务级脚本包含中文，验证源码按原编码保存。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        String source = "<route><!-- 中文 --><return value='addresses'/></route>";
        assertTrue(pool.getBucket("s").updateRoute(RouteTypeEnum.ServiceLevel, source));
        File home = this.temporary.newFolder();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS); //
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertTrue(cache.saveToZip(pool.getBucket("s"), bytes));

            // 从归档恢复服务级脚本，并检查重新编译后的状态。
            AddressBucket restored = new AddressBucket("s", "zone");
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray())) {
                cache.readFromZip(restored, input);
            }
            assertEquals(source, DiskCacheTestAccess.serviceScript(restored));
            assertTrue(DiskCacheTestAccess.serviceRuleEnabled(restored));
        }
    }

    @Test
    public void methodScriptSurvivesBucketZipRoundTrip() throws Exception {
        // 方法级脚本包含中文，验证源码按原编码保存。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        String source = "<route><!-- 中文 --><return value='addresses'/></route>";
        assertTrue(pool.getBucket("s").updateRoute(RouteTypeEnum.MethodLevel, source));
        File home = this.temporary.newFolder();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS); //
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertTrue(cache.saveToZip(pool.getBucket("s"), bytes));

            // 从归档恢复方法级脚本，并检查重新编译后的状态。
            AddressBucket restored = new AddressBucket("s", "zone");
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray())) {
                cache.readFromZip(restored, input);
            }
            assertEquals(source, DiskCacheTestAccess.methodScript(restored));
            assertTrue(DiskCacheTestAccess.methodRuleEnabled(restored));
        }
    }

    @Test
    public void argsScriptSurvivesBucketZipRoundTrip() throws Exception {
        // 参数级脚本包含中文，验证源码按原编码保存。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        String source = "<route><!-- 中文 --><return value='addresses'/></route>";
        assertTrue(pool.getBucket("s").updateRoute(RouteTypeEnum.ArgsLevel, source));
        File home = this.temporary.newFolder();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS); //
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertTrue(cache.saveToZip(pool.getBucket("s"), bytes));

            // 从归档恢复参数级脚本，并检查重新编译后的状态。
            AddressBucket restored = new AddressBucket("s", "zone");
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray())) {
                cache.readFromZip(restored, input);
            }
            assertEquals(source, DiskCacheTestAccess.argsScript(restored));
            assertTrue(DiskCacheTestAccess.argsRuleEnabled(restored));
        }
    }

    @Test
    public void restoreReadsExternallyCreatedServiceScriptEntry() throws Exception {
        // 独立编码服务级归档，避免写入端与读取端的条目名错误互相掩盖。
        String source = "<route><return value='{}'/></route>";
        byte[] snapshot = zipEntry("service-level.xml", source);
        AddressBucket bucket = new AddressBucket("s", "zone");
        AddressPool pool = new AddressPool();
        File home = this.temporary.newFolder();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS); //
             ByteArrayInputStream input = new ByteArrayInputStream(snapshot)) {
            // 读取固定名称的条目，恢复对应级别的脚本。
            cache.readFromZip(bucket, input);
            assertEquals(source, DiskCacheTestAccess.serviceScript(bucket));
        }
    }

    @Test
    public void restoreReadsExternallyCreatedMethodScriptEntry() throws Exception {
        // 独立编码方法级归档，避免写入端与读取端的条目名错误互相掩盖。
        String source = "<route><return value='{}'/></route>";
        byte[] snapshot = zipEntry("method-level.xml", source);
        AddressBucket bucket = new AddressBucket("s", "zone");
        assertTrue(bucket.updateRoute(RouteTypeEnum.ServiceLevel, source));
        AddressPool pool = new AddressPool();
        File home = this.temporary.newFolder();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS); //
             ByteArrayInputStream input = new ByteArrayInputStream(snapshot)) {
            // 恢复方法级脚本，同时保留原有的服务级脚本。
            cache.readFromZip(bucket, input);
            assertEquals(source, DiskCacheTestAccess.methodScript(bucket));
            assertEquals(source, DiskCacheTestAccess.serviceScript(bucket));
        }
    }

    @Test
    public void restoreReadsExternallyCreatedArgsScriptEntry() throws Exception {
        // 独立编码参数级归档，避免写入端与读取端的条目名错误互相掩盖。
        String source = "<route><return value='{}'/></route>";
        byte[] snapshot = zipEntry("args-level.xml", source);
        AddressBucket bucket = new AddressBucket("s", "zone");
        assertTrue(bucket.updateRoute(RouteTypeEnum.ServiceLevel, source));
        assertTrue(bucket.updateRoute(RouteTypeEnum.MethodLevel, source));
        AddressPool pool = new AddressPool();
        File home = this.temporary.newFolder();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS); //
             ByteArrayInputStream input = new ByteArrayInputStream(snapshot)) {
            // 恢复参数级脚本，同时保留原有的服务级和方法级脚本。
            cache.readFromZip(bucket, input);
            assertEquals(source, DiskCacheTestAccess.argsScript(bucket));
            assertEquals(source, DiskCacheTestAccess.serviceScript(bucket));
            assertEquals(source, DiskCacheTestAccess.methodScript(bucket));
        }
    }

    @Test
    public void fullSnapshotRestoresMultipleExistingServices() throws Exception {
        // 同一快照保存两个已注册服务，分别使用静态和动态地址。
        File home = this.temporary.newFolder();
        AddressPool source = new AddressPool();
        source.appendStaticAddress("s.one", this.a);
        source.appendAddress("s.two", this.b);
        try (DiskCache cache = new DiskCache(source, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            cache.storeConfig();
            assertTrue(new File(home, "snapshot/address.index").isFile());
        }

        // 新地址池预先登记服务名，再恢复磁盘中的地址。
        AddressPool restored = new AddressPool();
        restored.appendAddress("s.one", Collections.emptyList());
        restored.appendAddress("s.two", Collections.emptyList());
        try (DiskCache cache = new DiskCache(restored, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            cache.restoreConfig();
            assertEquals(Collections.singletonList(this.a), restored.queryAllAddresses("s.one"));
            assertEquals(Collections.singletonList(this.b), restored.queryAllAddresses("s.two"));
            assertEquals(Collections.singletonList(this.a), restored.queryAvailableAddresses("s.one"));
            assertEquals(this.a, restored.nextAddress("s.one", "get", null));
            assertEquals(this.b, restored.nextAddress("s.two", "get", null));
        }
    }

    @Test
    public void restoreDoesNotResurrectUnregisteredService() throws Exception {
        // 磁盘快照包含一个旧服务。
        File home = this.temporary.newFolder();
        AddressPool source = new AddressPool();
        source.appendAddress("s", this.a);
        try (DiskCache cache = new DiskCache(source, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            cache.storeConfig();
        }

        // 新地址池没有登记旧服务，恢复快照不能重新引入它。
        AddressPool restored = new AddressPool();
        try (DiskCache cache = new DiskCache(restored, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            cache.restoreConfig();
            assertTrue(restored.getBucketNames().isEmpty());
        }
    }

    @Test
    public void missingIndexDoesNotDestroyLiveAddresses() throws Exception {
        // 当前地址池已可路由，磁盘目录中尚未生成索引。
        File home = this.temporary.newFolder();
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        try (DiskCache cache = new DiskCache(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            // 缺少索引时恢复为空操作，当前地址仍然可用。
            cache.restoreConfig();
            assertEquals(this.a, pool.nextAddress("s", "get", null));
        }
    }

    @Test
    public void emptyIndexDoesNotDestroyLiveAddresses() throws Exception {
        // 准备空索引及当前有效地址。
        File home = this.temporary.newFolder();
        File snapshot = new File(home, "snapshot");
        assertTrue(snapshot.mkdirs());
        File index = new File(snapshot, "address.index");
        Files.write(index.toPath(), new byte[0]);
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        try (DiskCache cache = new DiskCache(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            // 空索引不能覆盖当前内存地址。
            cache.restoreConfig();
            assertEquals(this.a, pool.nextAddress("s", "get", null));
        }
    }

    @Test
    public void missingArchiveDoesNotDestroyLiveAddresses() throws Exception {
        // 索引指向不存在的归档。
        File home = this.temporary.newFolder();
        File snapshot = new File(home, "snapshot");
        assertTrue(snapshot.mkdirs());
        File index = new File(snapshot, "address.index");
        Files.write(index.toPath(), Collections.singletonList("absent.zip"), StandardCharsets.UTF_8);
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        try (DiskCache cache = new DiskCache(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            // 找不到归档时保留当前可路由地址。
            cache.restoreConfig();
            assertEquals(this.a, pool.nextAddress("s", "get", null));
        }
    }

    @Test
    public void brokenArchiveDoesNotDestroyLiveAddresses() throws Exception {
        // 索引指向无法读取 ZIP 条目的损坏归档。
        File home = this.temporary.newFolder();
        File snapshot = new File(home, "snapshot");
        assertTrue(snapshot.mkdirs());
        File archive = new File(snapshot, "broken.zip");
        Files.write(archive.toPath(), new byte[] { 1, 2, 3 });
        File index = new File(snapshot, "address.index");
        Files.write(index.toPath(), Collections.singletonList(archive.getName()), StandardCharsets.UTF_8);
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        try (DiskCache cache = new DiskCache(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            // 损坏归档不能清空当前可路由地址。
            cache.restoreConfig();
            assertEquals(this.a, pool.nextAddress("s", "get", null));
        }
    }

    @Test
    public void invalidAddressLineDoesNotDiscardOtherValidLines() throws Exception {
        // 在两个有效地址之间插入缺少单元信息的无效地址行。
        AddressBucket bucket = new AddressBucket("s", "zone");
        String lines = "# comment\nD|" + this.a + "\nD|rsf://127.0.0.9:8000/\nD|" + this.b + "\n";
        byte[] snapshot = zipEntry("address.sal", lines);
        AddressPool pool = new AddressPool();
        File home = this.temporary.newFolder();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS); //
             ByteArrayInputStream input = new ByteArrayInputStream(snapshot)) {
            // 单行解析失败后仍保留归档内其余有效地址。
            cache.readFromZip(bucket, input);
            Set<InterAddress> expectedAddresses = new HashSet<>(Arrays.asList(this.a, this.b));
            Set<InterAddress> restoredAddresses = new HashSet<>(bucket.getAllAddresses());
            assertEquals(expectedAddresses, restoredAddresses);
        }
    }

    @Test
    public void cleanupBeforeFirstSnapshotDoesNotCreateDirectory() throws Exception {
        // 缓存从未保存快照，数据目录中还没有快照子目录。
        File home = this.temporary.newFolder();
        File snapshot = new File(home, "snapshot");
        AddressPool pool = new AddressPool();
        assertFalse(snapshot.exists());
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            // 清理不存在的快照目录为空操作，也不能创建新的目录。
            cache.clearCacheData();
            assertFalse(snapshot.exists());
        }
    }

    @Test
    public void oldSnapshotsAreRemovedAndRecentOnesRetained() throws Exception {
        // 准备超过七天的归档及当前归档，验证清理的保留边界。
        File home = this.temporary.newFolder();
        File snapshot = new File(home, "snapshot");
        assertTrue(snapshot.mkdirs());
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss");
        long oldTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(9);
        String oldName = "addr-pool-" + format.format(new Date(oldTimestamp)) + ".zip";
        File old = new File(snapshot, oldName);
        File recent = new File(snapshot, "addr-pool-" + format.format(new Date()) + ".zip");
        assertTrue(old.createNewFile());
        assertTrue(recent.createNewFile());
        AddressPool pool = new AddressPool();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            // 同一次清理只移除过期文件，近期文件仍可恢复。
            cache.clearCacheData();
            assertFalse("Retention must match names written by storeConfig", old.exists());
            assertTrue(recent.exists());
        }
    }

    @Test
    public void scheduledRefreshRunsAgainAfterCompletion() throws Exception {
        // 通过短刷新周期观察真实定时器回调，不访问私有调度状态。
        BlockingQueue<Boolean> refreshes = new LinkedBlockingQueue<>();
        AddressPool pool = new AddressPool();
        File home = this.temporary.newFolder();
        try (DiskCache cache = new DiskCache(pool, home, 100, CACHE_INTERVAL_MS) {
            @Override
            protected void clearCacheData() {
                refreshes.add(true);
            }
        }) {
            // 连续两次回调证明任务完成后再次安排了刷新。
            assertNotNull("Maintenance must start", refreshes.poll(2, TimeUnit.SECONDS));
            assertNotNull("Maintenance must run again", refreshes.poll(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void closeCancelsOutstandingMaintenance() throws Exception {
        // 先确认刷新任务已经启动。
        BlockingQueue<Boolean> refreshes = new LinkedBlockingQueue<>();
        AddressPool pool = new AddressPool();
        File home = this.temporary.newFolder();
        try (DiskCache cache = new DiskCache(pool, home, 100, CACHE_INTERVAL_MS) {
            @Override
            protected void clearCacheData() {
                refreshes.add(true);
            }
        }) {
            assertNotNull("Maintenance must start", refreshes.poll(2, TimeUnit.SECONDS));

            // 关闭后丢弃既有事件，观察多个刷新周期都没有新的回调。
            cache.close();
            refreshes.clear();
            assertNull("Closed cache must stop maintenance", refreshes.poll(500, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void repeatedCloseDoesNotRestartMaintenance() throws Exception {
        // 启动实际刷新任务，为重复关闭准备已运行的资源。
        BlockingQueue<Boolean> refreshes = new LinkedBlockingQueue<>();
        AddressPool pool = new AddressPool();
        File home = this.temporary.newFolder();
        try (DiskCache cache = new DiskCache(pool, home, 100, CACHE_INTERVAL_MS) {
            @Override
            protected void clearCacheData() {
                refreshes.add(true);
            }
        }) {
            assertNotNull("Maintenance must start", refreshes.poll(2, TimeUnit.SECONDS));

            // 重复关闭保持幂等，定时维护仍然停止。
            cache.close();
            cache.close();
            refreshes.clear();
            assertNull("Repeated close must leave maintenance stopped", refreshes.poll(500, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void consecutiveSavesUseDifferentArchives() throws Exception {
        // 在同一目录连续保存，模拟同一时间窗口内的重复快照。
        File home = this.temporary.newFolder();
        AddressPool pool = new AddressPool();
        try (DiskCache cache = new DiskCache(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            cache.storeConfig();
            File index = new File(home, "snapshot/address.index");
            String first = new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8);

            // 第二次保存应发布新的文件名，第一次归档仍保留。
            cache.storeConfig();
            String second = new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8);
            assertNotEquals(first, second);
            assertTrue(new File(home, "snapshot/" + first).isFile());
            assertTrue(new File(home, "snapshot/" + second).isFile());
        }
    }

    @Test
    public void failedArchiveWritePreservesLastWorkingIndex() throws Exception {
        // 先发布一个完整快照并记录其索引。
        File home = this.temporary.newFolder();
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        try (DiskCache cache = new DiskCache(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            cache.storeConfig();
        }
        File indexFile = new File(home, "snapshot/address.index");
        byte[] index = Files.readAllBytes(indexFile.toPath());

        // 下一次归档写入失败时，调用者收到原因，已发布索引保持不变。
        try (DiskCache failing = new DiskCache(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS) {
            @Override
            protected boolean saveToZip(AddressBucket bucket, OutputStream out) throws IOException {
                throw new IOException("Injected archive write failure");
            }
        }) {
            IOException error = assertThrows(IOException.class, failing::storeConfig);
            assertEquals("Injected archive write failure", error.getMessage());
            byte[] remainingIndex = Files.readAllBytes(indexFile.toPath());
            assertArrayEquals(index, remainingIndex);
        }

        // 用新地址池恢复，确认失败后仍可使用上一次完整的恢复点。
        AddressPool restored = new AddressPool();
        restored.appendAddress("s", Collections.emptyList());
        try (DiskCache cache = new DiskCache(restored, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            cache.restoreConfig();
            assertEquals(this.a, restored.nextAddress("s", "get", null));
        }
    }

    @Test
    public void retentionPreservesIndexedRecoveryPoint() throws Exception {
        // 索引仍指向超过保留期的旧归档。
        File home = this.temporary.newFolder();
        File snapshot = new File(home, "snapshot");
        assertTrue(snapshot.mkdirs());
        File indexed = new File(snapshot, "addr-pool-20000101-000000.zip");
        assertTrue(indexed.createNewFile());
        File index = new File(snapshot, "address.index");
        Files.write(index.toPath(), Collections.singletonList(indexed.getName()), StandardCharsets.UTF_8);
        AddressPool pool = new AddressPool();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            // 清理必须保留当前索引引用的恢复点。
            cache.clearCacheData();
            assertTrue(indexed.exists());
        }
    }

    @Test
    public void retentionRemovesExpiredArchiveWithUniqueSuffix() throws Exception {
        // 同时准备索引保护的恢复点与带唯一后缀的另一份过期归档。
        File home = this.temporary.newFolder();
        File snapshot = new File(home, "snapshot");
        assertTrue(snapshot.mkdirs());
        File indexed = new File(snapshot, "addr-pool-20000101-000000.zip");
        File obsolete = new File(snapshot, "addr-pool-20000101-000001-abc123.zip");
        assertTrue(indexed.createNewFile());
        assertTrue(obsolete.createNewFile());
        File index = new File(snapshot, "address.index");
        Files.write(index.toPath(), Collections.singletonList(indexed.getName()), StandardCharsets.UTF_8);
        AddressPool pool = new AddressPool();
        try (DiskCacheTestAccess cache = new DiskCacheTestAccess(pool, home, CACHE_INTERVAL_MS, CACHE_INTERVAL_MS)) {
            // 同一次清理保留索引恢复点，同时删除未被引用的过期归档。
            cache.clearCacheData();
            assertFalse(obsolete.exists());
            assertTrue(indexed.exists());
        }
    }

    @Test
    public void refreshFailureStillSchedulesAnotherRun() throws Exception {
        // 清理回调记录进入事件后抛错，观察真实维护循环的失败恢复。
        BlockingQueue<Boolean> refreshes = new LinkedBlockingQueue<>();
        AddressPool pool = new AddressPool();
        File home = this.temporary.newFolder();
        try (DiskCache cache = new DiskCache(pool, home, 100, CACHE_INTERVAL_MS) {
            @Override
            protected void clearCacheData() {
                refreshes.add(true);
                throw new IllegalStateException("Injected cleanup failure");
            }
        }) {
            // 第二次进入清理回调证明第一次失败没有终止周期刷新。
            assertNotNull("Failing maintenance must start", refreshes.poll(2, TimeUnit.SECONDS));
            assertNotNull("Failed maintenance must run again", refreshes.poll(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void closeStopsMaintenanceAfterRefreshFailure() throws Exception {
        // 在实际清理回调中制造失败。
        BlockingQueue<Boolean> refreshes = new LinkedBlockingQueue<>();
        AddressPool pool = new AddressPool();
        File home = this.temporary.newFolder();
        try (DiskCache cache = new DiskCache(pool, home, 100, CACHE_INTERVAL_MS) {
            @Override
            protected void clearCacheData() {
                refreshes.add(true);
                throw new IllegalStateException("Injected cleanup failure");
            }
        }) {
            assertNotNull("Failing maintenance must start", refreshes.poll(2, TimeUnit.SECONDS));

            // 失败后关闭缓存，后续刷新周期也不能重新启动维护。
            cache.close();
            refreshes.clear();
            assertNull("Closed cache must not restart failed maintenance", refreshes.poll(500, TimeUnit.MILLISECONDS));
        }
    }

    private static byte[] zipEntry(String name, String content) throws IOException {
        // 独立生成一个 ZIP 条目，供读取端验证固定条目名称和文本内容。
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(content.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return bytes.toByteArray();
        }
    }
}
