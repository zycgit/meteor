/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
/**
 * 各种常量
 * <p>一个RSF数据包头的定义如下:
 *  <li>第 1 个二进制位:表示是否为 RSF 数据包,合法的数据为 0x80 (1000 0000)<li/>
 *  <li>第 2 ~ 4 个二进制位:表示数据包的分类,加上包头合法的数据为 0x80 ~ 0xF0 (1000 0000 ~ 1111 0000)<li/>
 *  <li>最后的 4 个二进制位,用于表示该分类包的版本。可选范围为:0~15 (0000 0000 ~ 0000 1111) <li/>
 * </p>
 * @version : 2014年9月20日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface MetConstants {
    String AddressList_ZipEntry        = "address.sal";
    String FlowControlRef_ZipEntry     = "flow-control.xml";
    String ServiceLevelScript_ZipEntry = "service-level.groovy";
    String MethodLevelScript_ZipEntry  = "method-level.groovy";
    String ArgsLevelScript_ZipEntry    = "args-level.groovy";
    String AddrPoolStoreName           = "addr-pool-";
    //
    String SnapshotPath                = "/snapshot";
    String SnapshotIndex               = "address.index";
    //
    long   OneHourTime                 = 60 * 60 * 1000;
    long   SevenDaysTime               = 7 * 24 * OneHourTime;
    //
    // ---------------------------------------------------------------------------------------------
    String LoggerName_Invoker          = "rsf-invoker";
    String LoggerName_Address          = "rsf-address";
}