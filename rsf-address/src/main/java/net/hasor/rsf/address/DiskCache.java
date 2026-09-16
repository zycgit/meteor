package net.hasor.rsf.address;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.timer.HashedWheelTimer;
import net.hasor.cobble.concurrent.timer.Timer;
import net.hasor.cobble.function.EFunction;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;

/**
 * 服务地址的辅助工具,负责读写本地地址本缓存。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2014年9月12日
 */
public class DiskCache implements Closeable {
    protected static final Logger logger                      = LoggerFactory.getLogger(DiskCache.class);
    private static final   String AddressList_ZipEntry        = "address.sal";
    private static final   String FlowControlRef_ZipEntry     = "flow-control.xml";
    private static final   String ServiceLevelScript_ZipEntry = "service-level.groovy";
    private static final   String MethodLevelScript_ZipEntry  = "method-level.groovy";
    private static final   String ArgsLevelScript_ZipEntry    = "args-level.groovy";
    private static final   String AddrPoolStoreName           = "addr-pool-";
    private static final   String SnapshotPath                = "/snapshot";
    private static final   String SnapshotIndex               = "address.index";

    private final        AddressPool   pool;
    private static final long          OneHourTime   = 3600000;
    private static final long  SevenDaysTime = 7 * 24 * OneHourTime;
    private final        Timer timer;
    private final        File  snapshotHome;
    private final File  indexFile;
    private final long refreshCacheMs;
    private final        long          diskCacheMs;
    private final        AtomicBoolean closed        = new AtomicBoolean();

    public DiskCache(AddressPool pool, File rsfDataHome, long refreshCacheMs, long diskCacheMs) {
        this.pool = Objects.requireNonNull(pool, "pool");
        if (refreshCacheMs <= 0 || diskCacheMs <= 0) {
            throw new IllegalArgumentException("Cache intervals must be positive");
        }

        this.refreshCacheMs = refreshCacheMs;
        this.diskCacheMs = Math.max(diskCacheMs, OneHourTime);
        this.snapshotHome = new File(rsfDataHome, SnapshotPath);
        this.indexFile = new File(snapshotHome, SnapshotIndex);

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        this.timer = new HashedWheelTimer(ThreadUtils.threadFactory(classLoader, "RSF-DiskCacheAddressPool-Timer-%s", true));
        scheduleRefresh();
        scheduleStore();
    }

    @Override
    public void close() {
        closed.set(true);
        this.timer.stop();
    }

    private void scheduleRefresh() {
        schedule(t -> doRefreshCache(), refreshCacheMs);
    }

    private void scheduleStore() {
        schedule(t -> doDiskCache(), diskCacheMs);
    }

    private void schedule(net.hasor.cobble.concurrent.timer.TimerTask task, long delay) {
        if (!closed.get()) {
            try {
                timer.newTimeout(task, delay, TimeUnit.MILLISECONDS);
            } catch (IllegalStateException e) {
                if (!closed.get()) {
                    throw e;
                }
            }
        }
    }

    private void doDiskCache() {
        if (closed.get()) {
            return;
        }
        try {
            this.storeConfig();
        } catch (Exception e) {
            logger.error("doDiskCache error " + e.getMessage(), e);
        } finally {
            scheduleStore();
        }
    }

    private void doRefreshCache() {
        if (closed.get()) {
            return;
        }
        try {
            this.pool.refreshAddressCache();
            this.clearCacheData();
        } catch (Exception e) {
            logger.error("doRefreshCache error " + e.getMessage(), e);
        } finally {
            scheduleRefresh();
        }
    }

    /** Keep the indexed recovery point, and remove other snapshots older than seven days. */
    protected synchronized void clearCacheData() {
        File[] files = snapshotHome.listFiles();
        if (files == null) {
            return;
        }

        String indexed = null;
        if (indexFile.isFile()) {
            try {
                List<String> lines = Files.readAllLines(indexFile.toPath(), StandardCharsets.UTF_8);
                indexed = lines.isEmpty() ? null : lines.get(0).trim();
            } catch (IOException e) {
                logger.error("Cannot read snapshot index during cleanup", e);
                return;
            }
        }

        Pattern pattern = Pattern.compile("addr-pool-([0-9]{8}-[0-9]{6})(?:-[A-Za-z0-9-]+)?\\.zip");
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss");
        format.setLenient(false);
        long cutoff = System.currentTimeMillis() - SevenDaysTime;
        for (File file : files) {
            Matcher matcher = pattern.matcher(file.getName());
            if (!file.isFile() || file.getName().equals(indexed) || !matcher.matches()) {
                continue;
            }

            try {
                if (format.parse(matcher.group(1)).getTime() < cutoff) {
                    Files.deleteIfExists(file.toPath());
                }
            } catch (java.text.ParseException e) {
                logger.warn("Ignoring snapshot with invalid date: " + file);
            } catch (IOException e) {
                logger.error("Cannot delete expired snapshot: " + file, e);
            }
        }
    }

    /** Write the archive completely before atomically publishing its index. */
    public synchronized void storeConfig() throws IOException {
        Path directory = snapshotHome.toPath();
        Files.createDirectories(directory);
        String name = AddrPoolStoreName + nowTime() + "-" + UUID.randomUUID() + ".zip";
        Path archive = directory.resolve(name);
        Path temporary = Files.createTempFile(directory, "archive-", ".tmp");
        Path indexTemporary = null;

        try {
            try (FileOutputStream out = new FileOutputStream(temporary.toFile())) {
                storeConfig(out);
                out.getFD().sync();
            }
            Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE);
            indexTemporary = Files.createTempFile(directory, "index-", ".tmp");
            try (FileOutputStream out = new FileOutputStream(indexTemporary.toFile())) {
                out.write(name.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(indexTemporary, indexFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
            if (indexTemporary != null) {
                Files.deleteIfExists(indexTemporary);
            }
        }
    }

    /** Restore only services registered in the live pool. */
    public synchronized void restoreConfig() {
        if (!indexFile.isFile()) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(indexFile.toPath(), StandardCharsets.UTF_8);
            String name = lines.isEmpty() ? "" : lines.get(0).trim();
            if (name.isEmpty() || !new File(name).getName().equals(name)) {
                return;
            }

            File archive = new File(snapshotHome, name);
            if (!archive.isFile()) {
                return;
            }

            try (InputStream in = Files.newInputStream(archive.toPath())) {
                restoreConfig(in);
            }
        } catch (IOException e) {
            logger.error("Cannot restore address snapshot: " + e.getMessage(), e);
        } finally {
            pool.refreshAddressCache();
        }
    }

    private static String nowTime() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    private void storeConfig(OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(nonClosing(out))) {
            pool.poolLock((EFunction<AddressPool, Object, IOException>) pool -> {
                for (AddressBucket bucket : pool.addressPool.values()) {
                    zip.putNextEntry(new ZipEntry(bucket.getServiceID() + ".zip"));
                    saveToZip(bucket, zip);
                    zip.closeEntry();
                }
                return null;
            });
        }
    }

    private void restoreConfig(InputStream in) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".zip")) {
                    String serviceID = name.substring(0, name.length() - 4);
                    AddressBucket bucket = pool.getBucket(serviceID);
                    if (bucket != null) {
                        try {
                            readFromZip(bucket, zip);
                        } catch (IOException e) {
                            logger.error("Cannot restore service " + serviceID, e);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    /** The caller owns out; finishing an inner ZIP must not close the outer archive. */
    protected boolean saveToZip(AddressBucket bucket, OutputStream out) throws IOException {
        synchronized (bucket) {
            try (ZipOutputStream zip = new ZipOutputStream(nonClosing(out))) {
                StringBuilder addresses = new StringBuilder();
                Set<InterAddress> staticAddresses = new HashSet<>(bucket.getStaticAddresses());
                for (InterAddress address : bucket.getAllAddresses()) {
                    addresses.append(staticAddresses.contains(address) ? "S|" : "D|");
                    addresses.append(address.toHostSchema()).append('\n');
                }

                writeEntry(zip, addresses.toString(), AddressList_ZipEntry);
                String control = bucket.getFlowControlRef().flowControlScript;
                if (control != null) {
                    writeEntry(zip, control, FlowControlRef_ZipEntry);
                }
                RuleRef rules = bucket.getRuleRef();
                writeEntry(zip, rules.getServiceLevel().getScript(), ServiceLevelScript_ZipEntry);
                writeEntry(zip, rules.getMethodLevel().getScript(), MethodLevelScript_ZipEntry);
                writeEntry(zip, rules.getArgsLevel().getScript(), ArgsLevelScript_ZipEntry);
            }
        }
        return true;
    }

    /** Read a complete service entry first, then apply valid addresses independently. */
    protected void readFromZip(AddressBucket bucket, InputStream in) throws IOException {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new FilterInputStream(in) {
            @Override
            public void close() { /* The outer archive belongs to the caller. */ }
        })) {

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                IOUtils.copy(zip, bytes);
                entries.put(entry.getName(), new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        List<InterAddress> staticAddresses = new ArrayList<>();
        List<InterAddress> dynamicAddresses = new ArrayList<>();
        String addresses = entries.get(AddressList_ZipEntry);
        if (addresses != null) {
            for (String line : addresses.split("\\r?\\n")) {
                if (!line.startsWith("S|") && !line.startsWith("D|")) {
                    continue;
                }

                try {
                    InterAddress address = new InterAddress(line.substring(2));
                    (line.startsWith("S|") ? staticAddresses : dynamicAddresses).add(address);
                } catch (URISyntaxException | IllegalArgumentException | IllegalStateException e) {
                    logger.warn("Ignoring malformed address in snapshot: " + line);
                }
            }
        }
        bucket.newAddress(staticAddresses, AddressTypeEnum.Static);
        bucket.newAddress(dynamicAddresses, AddressTypeEnum.Dynamic);
        if (entries.containsKey(FlowControlRef_ZipEntry)) {
            bucket.updateFlowControl(entries.get(FlowControlRef_ZipEntry));
        }

        restoreScript(bucket, entries, ServiceLevelScript_ZipEntry, RouteTypeEnum.ServiceLevel);
        restoreScript(bucket, entries, MethodLevelScript_ZipEntry, RouteTypeEnum.MethodLevel);
        restoreScript(bucket, entries, ArgsLevelScript_ZipEntry, RouteTypeEnum.ArgsLevel);
    }

    private static void restoreScript(AddressBucket bucket, Map<String, String> entries, String name, RouteTypeEnum type) {
        if (entries.containsKey(name)) {
            bucket.updateRoute(type, entries.get(name));
        }
    }

    private static OutputStream nonClosing(OutputStream out) {
        return new FilterOutputStream(out) {
            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
    }

    private static void writeEntry(ZipOutputStream zip, String body, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        if (body != null) {
            zip.write(body.getBytes(StandardCharsets.UTF_8));
        }
        zip.closeEntry();
    }
}