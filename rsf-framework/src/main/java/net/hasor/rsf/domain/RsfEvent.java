/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.domain;
/**
 * 事件名
 * @version : 2015年5月6日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface RsfEvent {
    /**发布服务*/
    String Rsf_ProviderService = "RsfEvent_ProviderService";
    /**订阅服务*/
    String Rsf_ConsumerService = "RsfEvent_ConsumerService";
    /**删除发布或订阅*/
    String Rsf_DeleteService   = "RsfEvent_DeleteService";
    /**应用上线*/
    String Rsf_Online          = "RsfEvent_Online";
    /**应用下线*/
    String Rsf_Offline         = "RsfEvent_Offline";
}