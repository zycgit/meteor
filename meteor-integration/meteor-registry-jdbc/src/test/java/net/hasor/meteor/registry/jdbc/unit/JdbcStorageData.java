/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.jdbc.unit;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.domain.*;

/** 存储契约所用业务正文；不创建会话，不执行登记或断言。 */
final class JdbcStorageData {
    static EntInstanceRuntimeData instance() {
        return instance("one", "orders", null);
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

    static EntServiceRuntimeData service() {
        EntServiceRuntimeData service = new EntServiceRuntimeData();
        service.setServiceId("echo");
        service.setRole(MetServiceType.Provider);
        service.setAddresses(new String[] { "rsf://127.0.0.1:8000" });
        return service;
    }

    static EntServiceRuntimeData service(String id, MetServiceType role, String... addresses) {
        EntServiceRuntimeData service = new EntServiceRuntimeData();
        service.setServiceId(id);
        service.setName(id);
        service.setRole(role);
        service.setAddresses(addresses);
        return service;
    }

    static EntityRecord<EntMetadataData> metadata() {
        EntityRecord<EntMetadataData> value = new EntityRecord<>();
        value.setData(new EntMetadataData());
        return value;
    }

    static RefProtocolMountData rsfEndpoint() {
        RefProtocolMountData endpoint = new RefProtocolMountData();
        endpoint.setProtocol("rsf");
        endpoint.setProtocolName("rsf");
        endpoint.setPublishedAddress("rsf://127.0.0.1:8000");
        return endpoint;
    }
}
