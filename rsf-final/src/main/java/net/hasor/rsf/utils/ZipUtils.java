/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.utils;
import net.hasor.cobble.setting.Settings;
import net.hasor.cobble.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 业务线程
 * @version : 2014年11月11日
 * @author 赵永春 (zyc@hasor.net)
 */
public class ZipUtils {
    protected final static Logger logger = LoggerFactory.getLogger(ZipUtils.class);

    public static void writeEntry(ZipOutputStream zipStream, String scriptBody, String entryName, String comment) throws IOException {
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