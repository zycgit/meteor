/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 将磁盘缓存操作与包内路由查询转发给单元测试。 */
public class DiskCacheTestAccess extends DiskCache {
    public DiskCacheTestAccess(AddressPool pool, File home, long refreshCacheMs, long diskCacheMs) {
        super(pool, home, refreshCacheMs, diskCacheMs);
    }

    @Override
    public boolean saveToZip(AddressBucket bucket, OutputStream output) throws IOException {
        return super.saveToZip(bucket, output);
    }

    @Override
    public void readFromZip(AddressBucket bucket, InputStream input) throws IOException {
        super.readFromZip(bucket, input);
    }

    @Override
    public void clearCacheData() {
        super.clearCacheData();
    }

    public static String serviceScript(AddressBucket bucket) {
        return bucket.getRuleRef().getServiceLevel().getScript();
    }

    public static boolean serviceRuleEnabled(AddressBucket bucket) {
        return bucket.getRuleRef().getServiceLevel().isEnable();
    }

    public static String methodScript(AddressBucket bucket) {
        return bucket.getRuleRef().getMethodLevel().getScript();
    }

    public static boolean methodRuleEnabled(AddressBucket bucket) {
        return bucket.getRuleRef().getMethodLevel().isEnable();
    }

    public static String argsScript(AddressBucket bucket) {
        return bucket.getRuleRef().getArgsLevel().getScript();
    }

    public static boolean argsRuleEnabled(AddressBucket bucket) {
        return bucket.getRuleRef().getArgsLevel().isEnable();
    }
}
