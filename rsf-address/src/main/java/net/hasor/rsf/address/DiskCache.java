package net.hasor.rsf.address;
import net.hasor.cobble.MatchUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.NameThreadFactory;
import net.hasor.cobble.concurrent.timer.HashedWheelTimer;
import net.hasor.cobble.concurrent.timer.Timer;
import net.hasor.cobble.function.EFunction;
import net.hasor.cobble.io.FilenameUtils;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 服务地址的辅助工具,负责读写本地地址本缓存。
 * @version : 2014年9月12日
 * @author 赵永春 (zyc@hasor.net)
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

    private final        AddressPool pool;
    private static final long        OneHourTime   = 3600000;
    private static final long        SevenDaysTime = 7 * 24 * OneHourTime;
    private              Timer       timer;
    private              File        snapshotHome;
    private              File        indexFile;

    public DiskCache(AddressPool pool, File rsfDataHome, long refreshCacheMs, long diskCacheMs) {
        this.pool = pool;
        this.snapshotHome = new File(rsfDataHome, SnapshotPath);
        this.indexFile = new File(snapshotHome, SnapshotIndex);
        long useDiskCacheMs = Math.max(diskCacheMs, OneHourTime);

        this.timer = new HashedWheelTimer(new NameThreadFactory("RSF-DiskCacheAddressPool-Timer-%s", Thread.currentThread().getContextClassLoader()));
        this.timer.newTimeout(t -> doRefreshCache(), refreshCacheMs, TimeUnit.MILLISECONDS);
        this.timer.newTimeout(t -> doDiskCache(), useDiskCacheMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        this.timer.stop();
    }

    private void doDiskCache() {
        try {
            logger.info("AddressPool - refreshCache. at = " + nowTime());
            this.pool.refreshAddressCache();
            this.storeConfig();
        } catch (Exception e) {
            logger.error("doDiskCache error " + e.getMessage(), e);
        }
    }

    private void doRefreshCache() {
        try {
            logger.info("AddressPool - refreshCache. at = " + nowTime());
            this.clearCacheData();
        } catch (Exception e) {
            logger.error("doRefreshCache error " + e.getMessage(), e);
        }
    }

    /** 清理缓存的地址数据 */
    protected void clearCacheData() {
        String[] fileNames = this.snapshotHome.list((dir, name) -> {
            return MatchUtils.wildToRegex("address-[0-9]{8}-[0-9]{6}.zip", name, MatchUtils.MatchTypeEnum.Regex);
        });
        List<String> sortList = (fileNames == null) ? new ArrayList<String>(0) : Arrays.asList(fileNames);
        Collections.sort(sortList);

        long nowTime = System.currentTimeMillis() - SevenDaysTime;//数据自动清理 7 天之前的数据
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss");
        for (String itemName : sortList) {
            try {
                String dateTimeStr = itemName.substring(AddrPoolStoreName.length(), itemName.length() - ".zip".length());
                Date dateTime = format.parse(dateTimeStr);
                if (dateTime.getTime() < nowTime) {
                    new File(this.snapshotHome, itemName).delete();
                }
            } catch (Exception e) { /**/ }
        }
    }

    /**保存地址列表到zip流中(每小时保存一次)，当遇到保存的文件已存在时会重新生成新的文件名。*/
    public synchronized void storeConfig() throws IOException {
        File writeFile = null;
        while (writeFile == null || writeFile.exists()) {
            writeFile = new File(this.snapshotHome, AddrPoolStoreName + nowTime() + ".zip");
        }
        logger.info("rsf - saveAddress to snapshot file({}) -> " + writeFile);
        FileOutputStream fos = null;
        FileWriter fw = null;
        try {
            boolean mkdirResult = writeFile.getParentFile().mkdirs();
            if (mkdirResult || writeFile.getParentFile().exists()) {
                fos = new FileOutputStream(writeFile, false);
                fos.getFD().sync();//独占文件

                this.storeConfig(fos);

                fos.flush();
                fos.close();

                fw = new FileWriter(this.indexFile, false);
                logger.info("rsf - update snapshot index -> " + this.indexFile.getAbsolutePath());
                fw.write(writeFile.getName());
                fw.flush();
                fw.close();
            }
        } catch (IOException e) {
            logger.error("rsf - saveAddress " + e.getClass().getSimpleName() + " :" + e.getMessage(), e);
            throw e;
        } finally {
            if (fos != null) {
                fos.close();
            }
            if (fw != null) {
                fw.close();
            }
        }
    }

    /**从保存的地址本中恢复数据。*/
    public synchronized void restoreConfig() {
        //1.校验
        if (!this.indexFile.exists()) {
            logger.info("address snapshot index file, undefined.");
            return;
        }
        if (!this.indexFile.canRead()) {
            logger.error("address snapshot index file, can not read.");
            return;
        }
        //2.确定要读取的文件。
        File readFile = null;
        try {
            FileReader reader = new FileReader(this.indexFile);
            List<String> bodyList = IOUtils.readLines(reader);
            String index = bodyList.isEmpty() ? "" : bodyList.get(0);
            readFile = new File(this.snapshotHome, index);
            if ("".equals(index) || !readFile.exists()) {
                logger.error("address snapshot '" + readFile + "' is not exist.");
                return;
            }
        } catch (Throwable e) {
            logger.error("read the snapshot file name error :" + e.getMessage(), e);
            return;
        }

        //3.恢复数据数据
        FileInputStream inStream = null;
        try {
            inStream = new FileInputStream(readFile);
            this.restoreConfig(inStream);
            inStream.close();
        } catch (IOException e) {
            logger.error("read the snapshot file name error :" + e.getMessage(), e);
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (IOException e1) {
                    logger.error(e1.getMessage(), e1);
                }
            }
        }
    }

    private static String nowTime() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    // ----------------------------------------- 配置的保存与恢复 -----------------------------------------

    /** 保存地址列表到zip流中 */
    private synchronized void storeConfig(OutputStream outStream) throws IOException {
        logger.info("rsf - saveAddress to stream.");
        try (ZipOutputStream zipStream = new ZipOutputStream(outStream)) {
            this.pool.poolLock((EFunction<AddressPool, Object, Throwable>) pool -> {
                for (AddressBucket bucket : pool.addressPool.values()) {
                    if (bucket != null) {
                        String serviceID = bucket.getServiceID() + ".zip";
                        logger.debug("rsf - service saveAddress " + serviceID + " storage to snapshot.");
                        ZipEntry entry = new ZipEntry(serviceID);
                        entry.setComment("service config of " + serviceID);
                        zipStream.putNextEntry(entry);
                        saveToZip(bucket, zipStream);
                        zipStream.closeEntry();
                    }
                }
                return null;
            });
        } catch (IOException e) {
            logger.error("rsf - saveAddress " + e.getClass().getSimpleName() + " :" + e.getMessage(), e);
            throw e;
        }
    }

    /**从保存的地址本中恢复数据。*/
    private synchronized void restoreConfig(InputStream inStream) throws IOException {
        ZipInputStream zipStream = new ZipInputStream(inStream);
        try {
            this.pool.poolLock((EFunction<AddressPool, Object, Throwable>) pool -> {
                ZipEntry zipEntry = null;
                while ((zipEntry = zipStream.getNextEntry()) != null) {
                    String serviceID = zipEntry.getName();
                    serviceID = FilenameUtils.getBaseName(serviceID);
                    AddressBucket bucket = pool.addressPool.get(serviceID);
                    if (bucket == null) {
                        continue;
                    }
                    this.readFromZip(bucket, zipStream);
                    zipStream.closeEntry();
                }
                return null;
            });
        } catch (Exception e) {
            logger.error("read the snapshot file error :" + e.getMessage(), e);
        }
    }

    /** 保存地址列表到zip流中 */
    protected boolean saveToZip(AddressBucket bucket, OutputStream outStream) throws IOException {
        String serviceID = bucket.getServiceID();
        List<InterAddress> allAddresses = bucket.getAllAddresses();
        List<InterAddress> staticAddresses = bucket.getStaticAddresses();

        boolean toSave = false;
        ZipOutputStream zipStream = new ZipOutputStream(outStream);
        zipStream.setComment("this config of " + serviceID);

        //1.服务地址本
        if (!allAddresses.isEmpty()) {
            toSave = true;
            StringBuilder strLogs = new StringBuilder();
            StringWriter strWriter = new StringWriter();
            BufferedWriter bfwriter = new BufferedWriter(strWriter);
            for (InterAddress inter : allAddresses) {
                if (staticAddresses.contains(inter)) {
                    strLogs.append(AddressTypeEnum.Static.getShortType());
                    bfwriter.append(AddressTypeEnum.Static.getShortType());
                } else {
                    strLogs.append(AddressTypeEnum.Dynamic.getShortType());
                    bfwriter.append(AddressTypeEnum.Dynamic.getShortType());
                }
                strLogs.append(inter.toString());
                strLogs.append(" , ");
                bfwriter.write(inter.toString());
                bfwriter.newLine();
            }
            bfwriter.flush();
            logger.info("bucket save list -> " + strLogs);
            try {
                String comment = "the address List of [" + serviceID + "] service.";
                writeEntry(zipStream, strWriter.toString(), AddressList_ZipEntry, comment);
                logger.info("bucket save to entry -> " + serviceID + " ,finish.");
            } catch (Exception e) {
                logger.error("bucket save to entry -> " + serviceID + " ,error -> " + e.getMessage(), e);
            }
        }

        //2.保存流控规则
        FlowControlRef flowControlRef = bucket.getFlowControlRef();
        if (flowControlRef != null && StringUtils.isNotBlank(flowControlRef.flowControlScript)) {
            try {
                toSave = true;
                String comment = "the flowControlRef of [" + serviceID + "] service.";
                writeEntry(zipStream, flowControlRef.flowControlScript, FlowControlRef_ZipEntry, comment);
                logger.info("flowControlRef save to entry -> " + serviceID + " ,finish.");
            } catch (Exception e) {
                logger.error("flowControlRef save to entry -> " + serviceID + " ,error -> " + e.getMessage(), e);
            }
        }

        //3.保存路由脚本
        RuleRef ruleRef = bucket.getRuleRef();
        if (ruleRef != null) {
            // - 服务级路由脚本
            if (StringUtils.isBlank(ruleRef.getServiceLevel().getScript())) {
                try {
                    toSave = true;
                    String comment = "the ServiceLevelScript of [" + serviceID + "] service.";
                    String script = ruleRef.getServiceLevel().getScript();
                    writeEntry(zipStream, script, ServiceLevelScript_ZipEntry, comment);
                    logger.info("ServiceLevelScript save to entry -> " + serviceID + " ,finish.");
                } catch (Exception e) {
                    logger.error("ServiceLevelScript save to entry -> " + serviceID + " ,error -> " + e.getMessage(), e);
                }
            }
            // - 方法级路由脚本
            if (StringUtils.isBlank(ruleRef.getMethodLevel().getScript())) {
                try {
                    toSave = true;
                    String comment = "the MethodLevelScript of [" + serviceID + "] service.";
                    String script = ruleRef.getMethodLevel().getScript();
                    writeEntry(zipStream, script, MethodLevelScript_ZipEntry, comment);
                    logger.info("MethodLevelScript save to entry -> " + serviceID + " ,finish.");
                } catch (Exception e) {
                    logger.error("MethodLevelScript save to entry -> " + serviceID + " ,error -> " + e.getMessage(), e);
                }
            }
            // - 参数级路由脚本
            if (StringUtils.isBlank(ruleRef.getArgsLevel().getScript())) {
                try {
                    toSave = true;
                    String comment = "the ArgsLevelScript of [" + serviceID + "] service.";
                    String script = ruleRef.getArgsLevel().getScript();
                    writeEntry(zipStream, script, ArgsLevelScript_ZipEntry, comment);
                    logger.info("ArgsLevelScript save to entry -> " + serviceID + " ,finish.");
                } catch (Exception e) {
                    logger.error("ArgsLevelScript save to entry -> " + serviceID + " ,error -> " + e.getMessage(), e);
                }
            }
        }
        //4.关闭输出
        if (toSave) {
            zipStream.finish();
            zipStream.closeEntry();
        }
        return toSave;
    }

    /** 从流中读取地址列表地址列表到zip流中 */
    protected void readFromZip(AddressBucket bucket, InputStream inStream) throws IOException {
        String serviceID = bucket.getServiceID();

        ZipInputStream zipStream = new ZipInputStream(inStream);
        Map<String, byte[]> dataMaps = new HashMap<>();
        ZipEntry zipEntry = null;
        while ((zipEntry = zipStream.getNextEntry()) != null) {
            ByteArrayOutputStream outArray = new ByteArrayOutputStream();
            IOUtils.copy(zipStream, outArray);
            dataMaps.put(zipEntry.getName(), outArray.toByteArray());
        }
        //1.服务地址本
        try {
            if (dataMaps.containsKey(AddressList_ZipEntry)) {                                       // 通
                InputStream dataIn = new ByteArrayInputStream(dataMaps.get(AddressList_ZipEntry));  // 用
                List<String> dataBody = IOUtils.readLines(dataIn, "UTF-8");                // 模
                if (!dataBody.isEmpty()) {                                                          // 式
                    logger.info("service " + serviceID + " read address form stream");
                    StringBuilder strBuffer = new StringBuilder();
                    ArrayList<InterAddress> staticNewHostSet = new ArrayList<>();
                    ArrayList<InterAddress> dynamicNewHostSet = new ArrayList<>();
                    for (String line : dataBody) {
                        if (StringUtils.isBlank(line) || line.startsWith("#")) {
                            continue;
                        }
                        try {
                            if (line.startsWith(AddressTypeEnum.Static.getShortType())) {
                                staticNewHostSet.add(new InterAddress(line.substring(2)));
                                strBuffer.append(line);
                                strBuffer.append(" , ");
                            } else if (line.startsWith(AddressTypeEnum.Dynamic.getShortType())) {
                                dynamicNewHostSet.add(new InterAddress(line.substring(2)));
                                strBuffer.append(line);
                                strBuffer.append(" , ");
                            }
                        } catch (URISyntaxException e) {
                            logger.info("read address '" + line + "' has URISyntaxException.");
                        }
                    }
                    logger.info("bucket read list -> " + strBuffer);
                    bucket.newAddress(staticNewHostSet, AddressTypeEnum.Static);
                    bucket.newAddress(dynamicNewHostSet, AddressTypeEnum.Dynamic);
                }
            }
        } catch (Throwable e) {
            logger.error("recoveryConfig address,failed-> serviceID =" + serviceID + " message=" + e.getMessage(), e);
        }
        //2.流控规则
        try {
            if (dataMaps.containsKey(FlowControlRef_ZipEntry)) {                                     // 通
                InputStream dataIn = new ByteArrayInputStream(dataMaps.get(FlowControlRef_ZipEntry));// 用
                List<String> dataBody = IOUtils.readLines(dataIn, "UTF-8");                 // 模
                if (!dataBody.isEmpty()) {                                                           // 式
                    String flowControl = StringUtils.join(dataBody.toArray(), "\n");
                    if (StringUtils.isNotBlank(flowControl)) {
                        bucket.updateFlowControl(flowControl);
                    }
                }
            }
        } catch (Throwable e) {
            logger.error("recoveryConfig flowControl,failed-> serviceID =" + serviceID + " message=" + e.getMessage(), e);
        }
        //3.服务级路由脚本策略
        try {
            if (dataMaps.containsKey(ServiceLevelScript_ZipEntry)) {                                     // 通
                InputStream dataIn = new ByteArrayInputStream(dataMaps.get(ServiceLevelScript_ZipEntry));// 用
                List<String> dataBody = IOUtils.readLines(dataIn, "UTF-8");                     // 模
                if (!dataBody.isEmpty()) {                                                               // 式
                    String scriptBody = StringUtils.join(dataBody.toArray(), "\n");
                    bucket.updateRoute(RouteTypeEnum.ServiceLevel, scriptBody);
                }
            }
        } catch (Throwable e) {
            logger.error("recoveryConfig serviceRoute,failed-> serviceID =" + serviceID + " message=" + e.getMessage(), e);
        }
        //4.方法级路由脚本策略
        try {
            if (dataMaps.containsKey(MethodLevelScript_ZipEntry)) {                                      // 通
                InputStream dataIn = new ByteArrayInputStream(dataMaps.get(MethodLevelScript_ZipEntry)); // 用
                List<String> dataBody = IOUtils.readLines(dataIn, "UTF-8");                     // 模
                if (!dataBody.isEmpty()) {                                                               // 式
                    String scriptBody = StringUtils.join(dataBody.toArray(), "\n");
                    bucket.updateRoute(RouteTypeEnum.MethodLevel, scriptBody);
                }
            }
        } catch (Throwable e) {
            logger.error("recoveryConfig methodRoute,failed-> serviceID =" + serviceID + " message=" + e.getMessage(), e);
        }
        //5.参数级路由脚本策略
        try {
            if (dataMaps.containsKey(ArgsLevelScript_ZipEntry)) {                                     // 通
                InputStream dataIn = new ByteArrayInputStream(dataMaps.get(ArgsLevelScript_ZipEntry));// 用
                List<String> dataBody = IOUtils.readLines(dataIn, "UTF-8");                  // 模
                if (!dataBody.isEmpty()) {                                                            // 式
                    String scriptBody = StringUtils.join(dataBody.toArray(), "\n");
                    bucket.updateRoute(RouteTypeEnum.ArgsLevel, scriptBody);
                }
            }
        } catch (Throwable e) {
            logger.error("recoveryConfig argsRoute,failed-> serviceID =" + serviceID + " message=" + e.getMessage(), e);
        }
    }

    private static void writeEntry(ZipOutputStream zipStream, String scriptBody, String entryName, String comment) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setComment(comment);
        zipStream.putNextEntry(entry);
        {
            OutputStreamWriter writer = new OutputStreamWriter(zipStream, StandardCharsets.UTF_8);
            BufferedWriter bfwriter = new BufferedWriter(writer);
            if (StringUtils.isBlank(scriptBody)) {
                bfwriter.write("");
            } else {
                bfwriter.write(scriptBody);
            }
            bfwriter.flush();
            writer.flush();
        }
        zipStream.closeEntry();
    }
}