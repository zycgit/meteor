/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;

/** 无资源的数据准备；存储的创建与关闭保留在测试方法中。 */
final class NacosTestData {
    private NacosTestData() {
    }

    static EntInstanceRuntimeData instance(String id) {
        EntInstanceRuntimeData value = new EntInstanceRuntimeData();
        value.setInstanceId(id);
        value.setApplicationName("orders");
        value.setIpAddress("127.0.0.1");
        value.setProcessId(123L);
        return value;
    }

    static EntServiceRuntimeData service(String id) {
        EntServiceRuntimeData value = new EntServiceRuntimeData();
        value.setServiceId(id);
        value.setName(id);
        value.setRole(MetServiceType.Provider);
        value.setAddresses(new String[] { "rsf://127.0.0.1:8000" });
        return value;
    }
}
