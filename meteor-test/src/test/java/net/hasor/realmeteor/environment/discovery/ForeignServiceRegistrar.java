/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.discovery;
import java.util.UUID;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.RefProtocolMountData;

/** 单个外部进程的显式登记适配；拥有自己的存储会话，不假冒外语 SDK 原生注册能力。 */
public final class ForeignServiceRegistrar implements AutoCloseable {
    private final StorageAccess storage;
    private final String        instanceId;
    private       String        serviceId;

    public ForeignServiceRegistrar(RegistryConfiguration configuration) throws Exception {
        this.storage = configuration.openStorage();
        this.instanceId = "foreign-" + UUID.randomUUID();
    }

    public void register(MetBindInfo<?> service, long processId, //
            InterAddress address, String contextPath) throws Exception {
        this.register(service, processId, "python", "xmlrpc", "http", address, contextPath);
    }

    /** 地址 scheme 对应 Meteor 挂载的发布 scheme；协议、传输和语言各自保留真实身份。 */
    public void register(MetBindInfo<?> service, long processId, String language, String protocol, //
            String transport, InterAddress address, String contextPath) throws Exception {
        // 外部进程已经报告实际监听地址；管理 RPC 地址保持空，不制造 Meteor 实例。
        String connectorName = language + "-" + transport;
        EntInstanceRuntimeData instance = new EntInstanceRuntimeData();
        instance.setInstanceId(this.instanceId);
        instance.setApplicationName(language + "-order-provider");
        instance.setIpAddress(address.getHost());
        instance.setProcessId(processId);
        instance.setUnitName(address.getFormUnit());
        instance.setRoles(new MetServiceType[] { MetServiceType.Provider });
        this.storage.registerInstance(instance);

        // 端点同时保留协议身份、context path 和注册地址，读取者不需要约定隐藏路径。
        RefProtocolMountData endpoint = new RefProtocolMountData();
        endpoint.setConnectorName(connectorName);
        endpoint.setTransport(transport);
        endpoint.setProtocolName(protocol);
        endpoint.setScheme(address.getSchema());
        endpoint.setContextPath(contextPath);
        endpoint.setBindEnabled(true);
        endpoint.setConfiguredAddress(address.toString());
        endpoint.setListenAddress(address.toString());
        endpoint.setPublishedAddress(address.toString());

        EntServiceRuntimeData registration = new EntServiceRuntimeData();
        registration.setServiceId(service.getBindID());
        registration.setGroup(service.getBindGroup());
        registration.setName(service.getBindName());
        registration.setVersion(service.getBindVersion());
        registration.setRole(MetServiceType.Provider);
        registration.setEndpoints(new RefProtocolMountData[] { endpoint });
        registration.setAddresses(new String[] { address.toString() });
        registration.setUsedProtocols(new String[] { protocol });
        this.storage.registerService(this.instanceId, registration);
        this.serviceId = service.getBindID();
    }

    public void withdraw() throws Exception {
        this.storage.unregisterService(this.instanceId, this.serviceId);
    }

    @Override
    public void close() throws Exception {
        this.storage.close();
    }
}
