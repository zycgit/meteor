/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client.support;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.rpc.*;
import net.hasor.meteor.registry.shared.storage.domain.EntConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.RefProtocolMountData;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 管理本地 Meteor 的运行状态、治理、快照和管理 RPC；并发控制由实际状态的持有者负责。 */
public final class ManagementRPCImpl implements ManagementRPC {
    private final MetContext       context;
    private final String           publishHost;
    private final GovernanceFilter governance = new GovernanceFilter();

    ////////////////////////////////////////////////////////////////////////////////////////
    // 本地运行服务与管理 RPC 生命周期

    /** 安装扩展时发布管理 RPC 和过滤器，生命周期与上下文一致。 */
    public ManagementRPCImpl(MetContext context, String publishHost) {
        this.context = context;
        this.publishHost = publishHost;
        if (this.context.getServiceInfo(ManagementRPC.class) != null) {
            throw new IllegalStateException("Management RPC is already registered on this Meteor context");
        }

        MetPublisher publisher = this.context.publisher();
        publisher.bindFilter(GovernanceFilter.FILTER_ID, this.governance);
        publisher.rsfService(ManagementRPC.class, this)//
                .asShadow()         //
                .asDistributedCall()//
                .register();
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // Client 协调入口：应用配置和发现结果，采集完整注册快照。
    Exception apply(String id, ConfigurationData defaults, ConfigurationData serviceConfig, ConfigurationData machine, ConfigurationData instanceConfig) {
        MetBindInfo<?> service = this.requireService(id);
        try {
            // 输入只参与这一次合并，不保存基线、覆盖层或已应用配置副本。
            this.requireValid(defaults);
            this.requireValid(serviceConfig);
            this.requireValid(machine);
            this.requireValid(instanceConfig);

            ConfigurationData config = effective(defaults, serviceConfig, machine, instanceConfig);
            this.applyCurrent(service, config);
            return null;
        } catch (Exception failure) {
            return failure;
        }
    }

    /** 获取业务服务，并移除已经注销服务的过滤器状态。 */
    List<String> serviceIds() {
        List<MetBindInfo<?>> services = this.bindings();
        List<String> ids = services.stream().map(MetBindInfo::getBindID).toList();
        this.governance.retainServices(ids);
        return ids;
    }

    /** 直接应用实例阻断治理，不修改应用运行选项。 */
    Exception applyInstance(EntConfigurationData configuration) {
        try {
            this.governance.setBlock(Boolean.TRUE.equals(configuration.getBlock()));
            return null;
        } catch (Exception failure) {
            return failure;
        }
    }

    /** 只采集服务登记和协议端点；运行配置、方法详情及地址本留在应用进程中。 */
    RegistrationSnapshot snapshot(String serviceId) throws Exception {
        RegistrationSnapshot record = new RegistrationSnapshot();
        record.setInstanceId(this.context.getInstanceID());
        if (serviceId == null) {
            InstanceData instance = this.instance();
            EntInstanceRuntimeData registration = RegistryJson.copy(instance, EntInstanceRuntimeData.class);
            registration.setManagementAddresses(instance.getManagementAddress());
            record.setInstance(registration);
        }

        List<MetBindInfo<?>> services;
        if (serviceId == null) {
            services = this.bindings();
        } else {
            services = List.of(this.requireService(serviceId));
        }
        for (MetBindInfo<?> service : services) {
            // 服务目录只保存稳定身份，不复制调用参数和完整方法签名。
            String id = service.getBindID();
            EntServiceRuntimeData registration = new EntServiceRuntimeData();
            registration.setServiceId(id);
            registration.setGroup(service.getBindGroup());
            registration.setName(service.getBindName());
            registration.setVersion(service.getBindVersion());
            registration.setType(service.getBindType().getName());
            registration.setRole(service.getServiceType());
            registration.setEnabled(this.governance.isServiceEnabled(id));
            RefProtocolMountData[] endpoints = RegistryJson.copy(this.endpoints(service), RefProtocolMountData[].class);
            registration.setEndpoints(endpoints);
            registration.setUsedProtocols(this.usedProtocols(service));
            boolean provider = service.getServiceType() == MetServiceType.Provider;

            // 已禁用、阻断或离线的提供者保留登记，但不向发现方提供地址。
            boolean available = this.context.isOnline() && !this.governance.isBlock() && registration.isEnabled();
            Set<String> published = new TreeSet<>();
            if (provider && available) {
                for (RefProtocolMountData endpoint : endpoints) {
                    if (endpoint.getListenAddress() != null) {
                        InterAddress listen = new InterAddress(endpoint.getListenAddress());
                        String address = this.toPublishAddress(listen).toString();
                        endpoint.setPublishedAddress(address);
                        published.add(address);
                    }
                }
            }

            registration.setAddresses(published.toArray(String[]::new));
            record.getServices().add(registration);
        }

        if (record.getInstance() != null) {
            Set<MetServiceType> roles = EnumSet.noneOf(MetServiceType.class);
            for (EntServiceRuntimeData service : record.getServices()) {
                roles.add(service.getRole());
            }
            record.getInstance().setRoles(roles.toArray(MetServiceType[]::new));
        }
        return record;
    }

    /** 按本地协议筛选并去重提供者地址，仅在结果变化时更新地址本。 */
    void updateAddresses(String serviceId, Collection<String> providers, boolean force) throws Exception {
        Set<String> schemes = new HashSet<>();
        for (ServiceEndpointData endpoint : this.endpoints(this.requireService(serviceId))) {
            schemes.add(endpoint.getScheme().toLowerCase(Locale.ROOT));
        }

        Set<InterAddress> supported = new HashSet<>();
        for (String value : providers) {
            InterAddress address = new InterAddress(value);
            if (schemes.contains(address.getSchema().toLowerCase(Locale.ROOT))) {
                supported.add(address);
            }
        }

        MetUpdater updater = this.context.getUpdater();
        Set<InterAddress> current = new HashSet<>(updater.queryAllAddresses(serviceId));
        // 标准地址池已有公开的静态地址查询能力；静态地址不参与发现结果比较。
        if (updater instanceof AddressPool pool) {
            List<InterAddress> fixed = pool.getBucket(serviceId).getStaticAddresses();
            current.removeAll(fixed);
            supported.removeAll(fixed);
        }
        if (force || !supported.equals(current)) {
            List<InterAddress> discovered = new ArrayList<>(supported);
            discovered.sort(Comparator.comparing(InterAddress::toString));
            updater.refreshAddress(serviceId, discovered);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // 直连管理：查询本地运行信息，修改只保存在当前进程。
    @Override
    public InstanceData instance() {
        InstanceData result = new InstanceData();
        result.setInstanceId(this.context.getInstanceID());
        result.setIpAddress(this.context.getSettings().getBindAddress());
        result.setProcessId(ProcessHandle.current().pid());
        result.setApplicationName(this.context.getApplicationName());
        result.setUnitName(this.context.getSettings().getUnitName());
        result.setBlock(this.governance.isBlock());
        for (ConnectorConfig connector : this.context.getSettings().getConnectorConfigs()) {
            if (!connector.bindEnabled()) {
                continue;
            }

            for (ProtocolConfig protocol : connector.protocols()) {
                InterAddress address = this.context.bindAddress(connector.name(), protocol.mountId());
                if (address != null) {
                    String addressKey = connector.name() + "#" + protocol.scheme();
                    result.getListenAddresses().putIfAbsent(addressKey, address.toString());
                    InterAddress managementAddress = address;
                    if (StringUtils.isNotBlank(this.publishHost)) {
                        managementAddress = new InterAddress(address.getSchema(), this.publishHost, address.getPort(), address.getFormUnit());
                    }
                    result.getManagementAddress().putIfAbsent(addressKey, managementAddress.toString());
                }
            }
        }
        return result;
    }

    @Override
    public ServiceData[] services() {
        List<ServiceData> services = new ArrayList<>();
        for (MetBindInfo<?> service : this.bindings()) {
            services.add(this.serviceSnapshot(service, this.endpoints(service)));
        }
        return services.toArray(ServiceData[]::new);
    }

    @Override
    public ServiceMemberData service(String serviceId) {
        // 直连查询只读取本地端点，不生成向注册中心发布的地址。
        MetBindInfo<?> service = this.requireService(serviceId);
        ServiceEndpointData[] endpoints = this.endpoints(service);
        ServiceMemberData member = new ServiceMemberData();
        member.setInstance(this.instance());
        member.setService(this.serviceSnapshot(service, endpoints));
        member.setEndpoints(endpoints);
        member.setUsedProtocols(this.usedProtocols(service));
        member.setEnabled(this.governance.isServiceEnabled(serviceId));
        member.setEffectiveConfiguration(this.effectiveConfiguration(serviceId));
        return RegistryJson.copy(member, ServiceMemberData.class);
    }

    @Override
    public boolean isServiceEnabled(String serviceId) {
        this.requireService(serviceId);
        return this.governance.isServiceEnabled(serviceId);
    }

    @Override
    public void setServiceEnabled(String serviceId, boolean enabled) {
        this.requireService(serviceId);
        this.governance.setServiceEnabled(serviceId, enabled);
    }

    @Override
    public boolean isBlock() {
        return this.governance.isBlock();
    }

    @Override
    public void setBlock(boolean block) {
        this.governance.setBlock(block);
    }

    @Override
    public String fetchServiceRule(String serviceId, RuleTypeData type) {
        this.requireService(serviceId);
        return this.readRule(serviceId, type);
    }

    @Override
    public String fetchInstanceRule(RuleTypeData type) {
        // 核心没有实例规则对象；仅当现有业务服务规则一致时返回共同值。
        List<String> services = this.serviceIds();
        if (services.isEmpty()) {
            return null;
        }
        String common = this.readRule(services.get(0), type);
        for (int i = 1; i < services.size(); i++) {
            String current = this.readRule(services.get(i), type);
            if (!StringUtils.equals(common, current)) {
                throw new IllegalStateException("Instance services use different rules; query the selected service");
            }
        }
        return common;
    }

    @Override
    public ValidationData validateServiceRule(String serviceId, RuleTypeData type, String value) {
        this.requireService(serviceId);

        return RegistryGovernanceUtils.validateRule(type, value);
    }

    @Override
    public void updateInstanceRule(RuleTypeData type, String rule) {
        this.updateRule(null, type, rule);
    }

    @Override
    public void updateServiceRule(String serviceId, RuleTypeData type, String rule) {
        this.updateRule(serviceId, type, rule);
    }

    private void updateRule(String serviceId, RuleTypeData type, String value) {
        ValidationData validation = RegistryGovernanceUtils.validateRule(type, value);
        if (!validation.isValid()) {
            throw new IllegalArgumentException(String.join("; ", validation.getErrors()));
        }
        List<String> services = serviceId == null ? this.serviceIds() : List.of(this.requireService(serviceId).getBindID());
        for (String id : services) {
            this.applyRule(id, type, value);
        }
    }

    @Override
    public Map<String, Object> fetchServiceMeta(String serviceId) {
        MetBindInfo<?> service = this.requireService(serviceId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String key : service.getMetaKeys()) {
            Object current = service.getMetaData(key);
            if (current != null) {
                metadata.put(key, current);
            }
        }
        return RegistryJson.copy(metadata, Map.class);
    }

    @Override
    public void putServiceMeta(String serviceId, Map<String, Object> values) {
        if (values == null || values.size() > MetBindInfo.MAX_METADATA_SIZE) {
            throw new IllegalArgumentException("Metadata must not exceed " + MetBindInfo.MAX_METADATA_SIZE + " entries");
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (StringUtils.isBlank(entry.getKey()) || entry.getValue() == null) {
                throw new IllegalArgumentException("Metadata requires nonblank keys and nonnull values");
            }
        }

        MetBindInfo<?> service = this.requireService(serviceId);
        service.setMetaData(values);
    }

    @Override
    public void removeServiceMeta(String serviceId, String[] keys) {
        if (keys == null) {
            throw new IllegalArgumentException("Metadata keys must not be null");
        }

        for (String key : keys) {
            if (StringUtils.isBlank(key)) {
                throw new IllegalArgumentException("Metadata key must not be blank");
            }
        }

        MetBindInfo<?> service = this.requireService(serviceId);
        for (String key : keys) {
            service.removeMetaData(key);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // 通过现有 API 应用配置；值只存于应用，不建立本地覆盖层。
    private ConfigurationData effectiveConfiguration(String id) {
        ConfigurationData result = new ConfigurationData();
        result.setEnabled(this.governance.isServiceEnabled(id));
        for (RuleTypeData type : RuleTypeData.values()) {
            setRule(result, type, this.readRule(id, type));
        }

        result.setMetadata(this.fetchServiceMeta(id));
        return result;
    }

    private void applyCurrent(MetBindInfo<?> service, ConfigurationData config) {
        String id = service.getBindID();
        for (RuleTypeData type : RuleTypeData.values()) {
            this.applyRule(id, type, rule(config, type));
        }

        this.applyMetadata(service, config.getMetadata());
        this.governance.setServiceEnabled(id, Boolean.TRUE.equals(config.getEnabled()));
    }

    private String readRule(String id, RuleTypeData type) {
        MetUpdater updater = this.context.getUpdater();
        return switch (type) {
            case SERVICE_ROUTE -> updater.serviceRoute(id);
            case METHOD_ROUTE -> updater.methodRoute(id);
            case ARGS_ROUTE -> updater.argsRoute(id);
            case FLOW_CONTROL -> updater.flowControl(id);
        };
    }

    private void applyRule(String id, RuleTypeData type, String value) {
        MetUpdater updater = this.context.getUpdater();
        boolean sameBody = StringUtils.equals(normalize(this.readRule(id, type)), normalize(value));
        if (sameBody) {
            return;
        }

        boolean applied = switch (type) {
            case SERVICE_ROUTE -> updater.updateServiceRoute(id, value);
            case METHOD_ROUTE -> updater.updateMethodRoute(id, value);
            case ARGS_ROUTE -> updater.updateArgsRoute(id, value);
            case FLOW_CONTROL -> updater.updateFlowControl(id, value);
        };

        if (!applied) {
            throw new IllegalArgumentException("Cannot apply " + type + " to " + id);
        }
    }

    private void requireValid(ConfigurationData value) {
        List<String> errors = new ArrayList<>();
        if (value == null) {
            errors.add("configuration is required");
        } else {
            for (RuleTypeData type : RuleTypeData.values()) {
                if (!Boolean.FALSE.equals(value.getRuleEnabled().get(type))) {
                    String script = rule(value, type);

                    ValidationData validation = RegistryGovernanceUtils.validateRule(type, script);
                    Collections.addAll(errors, validation.getErrors());
                }
            }

            if (value.getMetadata() != null && value.getMetadata().size() > MetBindInfo.MAX_METADATA_SIZE) {
                errors.add("Metadata cannot exceed " + MetBindInfo.MAX_METADATA_SIZE + " entries");
            }

            try {
                RegistryJson.write(value.getMetadata());
            } catch (Exception failure) {
                errors.add("metadata must be JSON data");
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static String rule(ConfigurationData config, RuleTypeData type) {
        if (type == null) {
            throw new IllegalArgumentException("Rule type is required");
        }

        return switch (type) {
            case SERVICE_ROUTE -> config.getServiceRoute();
            case METHOD_ROUTE -> config.getMethodRoute();
            case ARGS_ROUTE -> config.getArgsRoute();
            case FLOW_CONTROL -> config.getFlowControl();
        };
    }

    private void applyMetadata(MetBindInfo<?> service, Map<String, Object> desired) {
        Map<String, Object> values = desired == null ? Map.of() : desired;
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : service.getMetaKeys()) {
            if (!values.containsKey(key)) {
                changes.put(key, null);
            }
        }

        changes.putAll(values);
        // 将删除与写入一次提交给应用，校验失败时不提前清空旧值。
        service.setMetaData(changes);
    }

    private static String normalize(String value) {
        return StringUtils.isBlank(value) ? "" : value;
    }

    private static ConfigurationData effective(ConfigurationData defaults, ConfigurationData service, ConfigurationData machine, ConfigurationData instance) {
        ConfigurationData result = new ConfigurationData();
        result.setEnabled(!Boolean.FALSE.equals(service.getEnabled()));
        result.setEnabled(Boolean.TRUE.equals(result.getEnabled()) && !Boolean.FALSE.equals(instance.getEnabled()));

        for (RuleTypeData type : RuleTypeData.values()) {
            ConfigurationData selected = selectRuleScope(type, instance, machine, service, defaults);
            if (selected != null) {
                boolean disabled = Boolean.FALSE.equals(selected.getRuleEnabled().get(type));
                setRule(result, type, disabled ? "" : rule(selected, type));
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();

        if (defaults.getMetadata() != null) {
            metadata.putAll(defaults.getMetadata());
        }
        if (service.getMetadata() != null) {
            metadata.putAll(service.getMetadata());
        }
        if (machine.getMetadata() != null) {
            metadata.putAll(machine.getMetadata());
        }
        if (instance.getMetadata() != null) {
            metadata.putAll(instance.getMetadata());
        }

        if (metadata.size() > MetBindInfo.MAX_METADATA_SIZE) {
            throw new IllegalArgumentException("Effective metadata cannot exceed " + MetBindInfo.MAX_METADATA_SIZE + " entries");
        } else {
            result.setMetadata(metadata.isEmpty() ? null : metadata);
            return RegistryJson.copy(result, ConfigurationData.class);
        }
    }

    private static ConfigurationData selectRuleScope(RuleTypeData type, ConfigurationData... scopes) {
        for (ConfigurationData config : scopes) {
            if (Boolean.FALSE.equals(config.getRuleEnabled().get(type))) {
                return config;
            }

            String rule = rule(config, type);
            if (rule != null) {
                return config;
            }
        }
        return null;
    }

    private static void setRule(ConfigurationData config, RuleTypeData type, String rule) {
        switch (type) {
            case SERVICE_ROUTE -> config.setServiceRoute(rule);
            case METHOD_ROUTE -> config.setMethodRoute(rule);
            case ARGS_ROUTE -> config.setArgsRoute(rule);
            case FLOW_CONTROL -> config.setFlowControl(rule);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // 本地信息采集：查询与注册上报共用，发布地址只在注册快照中生成。
    private List<MetBindInfo<?>> bindings() {
        List<MetBindInfo<?>> result = new ArrayList<>();
        for (String id : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(id);
            if (service != null && !service.isShadow()) {
                result.add(service);
            }
        }

        return result;
    }

    private MetBindInfo<?> requireService(String id) {
        MetBindInfo<?> service = this.context.getServiceInfo(id);
        if (service == null || service.isShadow()) {
            throw new IllegalArgumentException("Unknown business service: " + id);
        }

        return service;
    }

    private ServiceData serviceSnapshot(MetBindInfo<?> service, ServiceEndpointData[] endpoints) {
        ServiceData result = new ServiceData();
        result.setServiceId(service.getBindID());
        result.setGroup(service.getBindGroup());
        result.setName(service.getBindName());
        result.setVersion(service.getBindVersion());
        result.setType(service.getBindType().getName());
        result.setRole(service.getServiceType());
        if (service.getServiceType() == MetServiceType.Provider) {
            result.setProviderCount(1);
        } else {
            result.setConsumerCount(1);
        }

        Set<String> protocols = new TreeSet<>();
        for (ServiceEndpointData endpoint : endpoints) {
            protocols.add(endpoint.getProtocolName());
        }
        result.setProtocols(protocols.toArray(String[]::new));
        return result;
    }

    private String[] usedProtocols(MetBindInfo<?> service) {
        if (service.getServiceType() != MetServiceType.Consumer) {
            return new String[0];
        }

        Set<String> protocols = new TreeSet<>();
        for (InterAddress address : this.context.getUpdater().queryAllAddresses(service.getBindID())) {
            protocols.add(address.getSchema().toLowerCase(Locale.ROOT));
        }
        return protocols.toArray(String[]::new);
    }

    private ServiceEndpointData[] endpoints(MetBindInfo<?> service) {
        List<ServiceEndpointData> result = new ArrayList<>();
        Set<String> protocols = service.getBindProtocols();
        for (ConnectorConfig connector : this.context.getSettings().getConnectorConfigs()) {
            boolean provider = service.getServiceType() == MetServiceType.Provider;
            if (provider && !connector.bindEnabled()) {
                continue;
            }

            for (ProtocolConfig protocol : connector.protocols()) {
                if (!protocol.accepts(protocols)) {
                    continue;
                }

                // 服务只上报实际选择的上下文；不使用上下文的协议仍保留一个端点。
                List<String> paths = protocol.contextPaths(service);
                if (protocol.contextPaths().isEmpty()) {
                    paths = Collections.singletonList(null);
                }

                InterAddress listen = connector.bindEnabled() ? this.context.bindAddress(connector.name(), protocol.mountId()) : null;
                for (String path : paths) {
                    ServiceEndpointData endpoint = new ServiceEndpointData();
                    endpoint.setConnectorName(connector.name());
                    endpoint.setTransport(connector.transportType());
                    endpoint.setProtocolName(protocol.name());
                    endpoint.setProtocol(protocol.factoryName());
                    endpoint.setScheme(protocol.scheme());
                    endpoint.setContextPath(path);
                    endpoint.setBindEnabled(connector.bindEnabled());
                    endpoint.setConfiguredAddress(connector.address().toString());
                    endpoint.setListenAddress(listen == null ? null : listen.toString());
                    result.add(endpoint);
                }
            }
        }

        return result.toArray(ServiceEndpointData[]::new);
    }

    private InterAddress toPublishAddress(InterAddress address) throws Exception {
        if (StringUtils.isNotBlank(this.publishHost)) {
            return new InterAddress(address.getSchema(), this.publishHost, address.getPort(), address.getFormUnit());
        }
        if (address.toSocketAddress().getAddress().isAnyLocalAddress()) {
            throw new IllegalArgumentException("Set registry publishHost when binding a wildcard address");
        }
        return address;
    }

}
