/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.zookeeper.unit;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;

/** 存储契约测试共用的最小登记正文，不创建连接或执行登记。 */
final class ZookeeperRecords {
    private ZookeeperRecords() {
    }

    static EntInstanceRuntimeData instance(String id, String application, String unit) {
        EntInstanceRuntimeData instance = new EntInstanceRuntimeData();
        instance.setInstanceId(id);
        instance.setApplicationName(application);
        instance.setUnitName(unit);
        instance.setIpAddress("127.0.0.1");
        instance.setProcessId(123L);
        return instance;
    }

    static EntServiceRuntimeData service(String id, MetServiceType role, String... addresses) {
        EntServiceRuntimeData service = new EntServiceRuntimeData();
        service.setServiceId(id);
        service.setName(id);
        service.setRole(role);
        service.setAddresses(addresses);
        return service;
    }
}
