/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client.support;
import java.lang.reflect.Method;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.*;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.rpc.*;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceConfigurationData;
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
        publisher.rsfService(ManagementRPC.class, this).asShadow().register();
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // Client 协调入口：应用配置和发现结果，采集完整注册快照。
    public Exception apply(String id, ConfigurationData defaults, ConfigurationData serviceConfig, ConfigurationData machine, ConfigurationData instanceConfig) {
        this.requireService(id);
        try {
            // 输入只参与这一次合并，不保存基线、覆盖层或已应用配置副本。
            this.requireValid(defaults);
            this.requireValid(serviceConfig);
            this.requireValid(machine);
            this.requireValid(instanceConfig);

            ConfigurationData config = effective(defaults, serviceConfig, machine, instanceConfig);
            this.applyCurrent(id, config);
            return null;
        } catch (Exception failure) {
            return failure;
        }
    }

    /** 获取业务服务，并移除已经注销服务的过滤器状态。 */
    public List<String> serviceIds() {
        List<MetBindInfo<?>> services = this.bindings();
        List<String> ids = services.stream().map(MetBindInfo::getBindID).toList();
        this.governance.retainServices(ids);
        return ids;
    }

    /** 直接应用实例配置；不恢复历史选项，显式 null 值删除选项。 */
    public Exception applyInstance(EntInstanceConfigurationData configuration) {
        try {
            this.applySettings(configuration);
            return null;
        } catch (Exception failure) {
            return failure;
        }
    }

    /** 采集完整注册快照；指定服务时只携带实例 ID 和该服务的信息、地址本。 */
    public RegistrationSnapshot snapshot(String serviceId) throws Exception {
        RegistrationSnapshot record = new RegistrationSnapshot();
        record.setServices(this.members(serviceId));
        if (serviceId == null) {
            record.setInstance(this.instance());
            record.setSettings(this.settings());
        } else {
            InstanceData instance = new InstanceData();
            instance.setInstanceId(this.context.getInstanceID());
            record.setInstance(instance);
        }

        for (ServiceMemberData member : record.getServices()) {
            Set<String> published = new TreeSet<>();
            boolean provider = StringUtils.equals("Provider", member.getService().getRole());
            boolean publish = this.context.isOnline() && !this.governance.isBlock() && member.isEnabled() && provider;
            if (publish) {
                for (ServiceEndpointData endpoint : member.getEndpoints()) {
                    if (endpoint.getListenAddress() != null) {
                        InterAddress listen = new InterAddress(endpoint.getListenAddress());
                        String address = this.toPublishAddress(listen).toString();
                        endpoint.setPublishedAddress(address);
                        published.add(address);
                    }
                }
            }
            member.setAddresses(published.toArray(String[]::new));
            String id = member.getService().getServiceId();
            record.getAddressBooks().put(id, this.addresses(id));
        }
        return record;
    }

    /** 按本地协议筛选并去重提供者地址，仅在结果变化时更新地址本。 */
    public void updateAddresses(String serviceId, Collection<String> providers) throws Exception {
        this.updateAddresses(serviceId, providers, false);
    }

    public void updateAddresses(String serviceId, Collection<String> providers, boolean force) throws Exception {
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
    public AddressData addresses(String serviceId) {
        this.requireService(serviceId);
        MetUpdater updater = this.context.getUpdater();
        AddressData result = new AddressData();
        result.setAvailable(strings(updater.queryAvailableAddresses(serviceId)));
        result.setInvalid(strings(updater.queryInvalidAddresses(serviceId)));
        result.setLocalUnit(strings(updater.queryLocalUnitAddresses(serviceId)));
        return result;
    }

    @Override
    public InstanceData instance() {
        InstanceData result = new InstanceData();
        result.setInstanceId(this.context.getInstanceID());
        result.setApplicationName(this.context.getApplicationName());
        result.setUnitName(this.context.getSettings().getUnitName());
        result.setBlock(this.governance.isBlock());
        for (String protocol : this.context.runProtocols()) {
            InterAddress address = this.context.bindAddress(protocol);
            if (address != null) {
                result.getListenAddresses().put(protocol, address.toString());
                InterAddress managementAddress = address;
                if (StringUtils.isNotBlank(this.publishHost)) {
                    managementAddress = new InterAddress(address.getSchema(), this.publishHost, address.getPort(), address.getFormUnit());
                }
                result.getManagementAddress().put(protocol, managementAddress.toString());
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> settings() {
        MetSettings settings = this.context.getSettings();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationName", this.context.getApplicationName());
        result.put("defaultTimeout", settings.getDefaultTimeout());
        result.put("defaultGroup", settings.getDefaultGroup());
        result.put("defaultVersion", settings.getDefaultVersion());
        result.put("queueMaxSize", settings.getQueueMaxSize());
        result.put("queueMinPoolSize", settings.getQueueMinPoolSize());
        result.put("queueMaxPoolSize", settings.getQueueMaxPoolSize());
        result.put("queueKeepAliveTime", settings.getQueueKeepAliveTime());
        result.put("requestTimeout", settings.getRequestTimeout());
        result.put("maximumRequest", settings.getMaximumRequest());
        result.put("connectTimeout", settings.getConnectTimeout());
        result.put("bindAddress", settings.getBindAddress());
        result.put("defaultProtocol", settings.getDefaultProtocol());
        result.put("unitName", settings.getUnitName());
        result.put("invalidWaitTime", settings.getInvalidWaitTime());
        result.put("refreshCacheTime", settings.getRefreshCacheTime());
        result.put("diskCacheTimeInterval", settings.getDiskCacheTimeInterval());
        result.put("localDiskCache", settings.isLocalDiskCache());
        result.put("automaticOnline", settings.isAutomaticOnline());
        result.put("dataHome", settings.getDataHome() == null ? null : settings.getDataHome().toString());
        result.put("sendLimitPolicy", settings.getSendLimitPolicy().name());
        result.put("protocols", new ArrayList<>(settings.getProtocols()));
        result.put("requestOptions", options(settings.getRequestOptions()));
        result.put("responseOptions", options(settings.getResponseOptions()));

        Map<String, String> bindings = new LinkedHashMap<>();
        for (String protocol : settings.getProtocols()) {
            InterAddress address = settings.getBindAddressSet(protocol);
            bindings.put(protocol, address == null ? null : address.toString());
        }
        result.put("bindAddressSet", bindings);

        List<Map<String, Object>> connectors = new ArrayList<>();
        for (ConnectorConfig connector : settings.getConnectorConfigs()) {
            Map<String, Object> endpoint = new LinkedHashMap<>();
            endpoint.put("name", connector.name());
            endpoint.put("listenType", connector.listenType());
            endpoint.put("bindEnabled", connector.bindEnabled());
            endpoint.put("address", connector.address().toString());
            endpoint.put("options", new LinkedHashMap<>(connector.options()));
            List<Map<String, Object>> protocols = new ArrayList<>();
            for (ProtocolConfig protocol : connector.protocols()) {
                Map<String, Object> mount = new LinkedHashMap<>();
                mount.put("name", protocol.name());
                mount.put("scheme", protocol.scheme());
                mount.put("protocol", protocol.protocol());
                mount.put("options", new LinkedHashMap<>(protocol.options()));
                protocols.add(mount);
            }
            endpoint.put("protocols", protocols);
            connectors.add(endpoint);
        }
        result.put("connectorConfigs", connectors);
        return result;
    }

    @Override
    public ServiceInfoData[] services() {
        List<ServiceInfoData> services = new ArrayList<>();
        for (MetBindInfo<?> service : this.bindings()) {
            services.add(RegistryJson.copy(this.serviceSnapshot(service), ServiceInfoData.class));
        }
        return services.toArray(ServiceInfoData[]::new);
    }

    @Override
    public ServiceMemberData service(String serviceId) {
        // 直连查询只读取本地端点，不生成向注册中心发布的地址。
        return this.members(serviceId)[0];
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
    public void setRequestOption(String name, String value) {
        this.setOption(true, name, value);
    }

    @Override
    public void setResponseOption(String name, String value) {
        this.setOption(false, name, value);
    }

    private void setOption(boolean request, String name, String value) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Option name must not be blank");
        }
        MetSettings settings = this.context.getSettings();
        MetOptionSet options = request ? settings.getRequestOptions() : settings.getResponseOptions();
        setOption(options, name, value);
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
    private void applySettings(EntInstanceConfigurationData data) {
        Map<String, String> requests = data.getRequestOptions() == null ? Map.of() : data.getRequestOptions();
        Map<String, String> responses = data.getResponseOptions() == null ? Map.of() : data.getResponseOptions();

        for (String key : requests.keySet()) {
            if (StringUtils.isBlank(key)) {
                throw new IllegalArgumentException("Request option name is required");
            }
        }
        for (String key : responses.keySet()) {
            if (StringUtils.isBlank(key)) {
                throw new IllegalArgumentException("Response option name is required");
            }
        }

        applyOptions(this.context.getSettings().getRequestOptions(), requests);
        applyOptions(this.context.getSettings().getResponseOptions(), responses);
        this.governance.setBlock(Boolean.TRUE.equals(data.getBlock()));
    }

    private static void applyOptions(MetOptionSet options, Map<String, String> desired) {
        for (Map.Entry<String, String> entry : desired.entrySet()) {
            setOption(options, entry.getKey(), entry.getValue());
        }
    }

    private static void setOption(MetOptionSet options, String key, String value) {
        if (value == null) {
            options.removeOption(key);
        } else {
            options.addOption(key, value);
        }
    }

    private ConfigurationData effectiveConfiguration(String id) {
        MetBindInfo<?> service = this.requireService(id);
        ConfigurationData result = new ConfigurationData();
        result.setEnabled(this.governance.isServiceEnabled(id));
        for (RuleTypeData type : RuleTypeData.values()) {
            setRule(result, type, this.readRule(id, type));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String key : service.getMetaKeys()) {
            Object current = service.getMetaData(key);
            if (current != null) {
                metadata.put(key, current);
            }
        }

        result.setMetadata(metadata);
        return result;
    }

    private void applyCurrent(String id, ConfigurationData config) {
        MetBindInfo<?> service = this.requireService(id);
        for (RuleTypeData type : RuleTypeData.values()) {
            this.applyRule(id, type, rule(config, type));
        }

        this.applyMetadata(service, config.getMetadata());
        this.setServiceEnabled(id, Boolean.TRUE.equals(config.getEnabled()));
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
    private boolean isBusinessService(MetBindInfo<?> service) {
        return !service.isShadow();
    }

    private List<MetBindInfo<?>> bindings() {
        List<MetBindInfo<?>> result = new ArrayList<>();
        for (String id : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(id);
            if (service != null && this.isBusinessService(service)) {
                result.add(service);
            }
        }

        return result;
    }

    private ServiceMemberData[] members(String serviceId) {
        List<ServiceMemberData> result = new ArrayList<>();
        List<MetBindInfo<?>> services;
        if (serviceId == null) {
            services = this.bindings();
        } else {
            services = List.of(this.requireService(serviceId));
        }

        for (MetBindInfo<?> service : services) {
            ServiceMemberData member = new ServiceMemberData();
            member.setInstance(this.instance());
            member.setService(this.serviceSnapshot(service));
            member.setEndpoints(this.endpoints(service));
            member.setEnabled(this.governance.isServiceEnabled(service.getBindID()));
            member.setEffectiveConfiguration(this.effectiveConfiguration(service.getBindID()));
            result.add(member);
        }

        return RegistryJson.copy(result, ServiceMemberData[].class);
    }

    private static String[] strings(List<InterAddress> values) {
        if (values == null) {
            return new String[0];
        }

        return values.stream().map(InterAddress::toString).toArray(String[]::new);
    }

    private MetBindInfo<?> requireService(String id) {
        MetBindInfo<?> service = this.context.getServiceInfo(id);
        if (service == null || !this.isBusinessService(service)) {
            throw new IllegalArgumentException("Unknown business service: " + id);
        }

        return service;
    }

    private ServiceData serviceSnapshot(MetBindInfo<?> service) {
        ServiceData result = new ServiceData();
        result.setServiceId(service.getBindID());
        result.setGroup(service.getBindGroup());
        result.setName(service.getBindName());
        result.setVersion(service.getBindVersion());
        result.setType(service.getBindType().getName());
        result.setRole(service.getServiceType().name());
        if (service.getServiceType() == MetServiceType.Provider) {
            result.setProviderCount(1);
        } else {
            result.setConsumerCount(1);
        }

        if (service.getBindProtocols() != null) {
            result.setProtocols(service.getBindProtocols().stream().sorted().toArray(String[]::new));
        }
        for (String alias : service.getAliasTypes()) {
            result.getAliases().put(alias, service.getAliasName(alias));
        }

        // 与 RPC 调用使用相同的方法范围，不上报被忽略的方法。
        List<String> methods = new ArrayList<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            StringJoiner parameters = new StringJoiner(", ");
            for (Class<?> parameter : method.getParameterTypes()) {
                parameters.add(parameter.getTypeName());
            }
            String signature = method.getReturnType().getTypeName() + " " + method.getName() + "(" + parameters + ")";
            methods.add(signature);
        }

        result.setMethods(methods.stream().sorted().toArray(String[]::new));
        result.setTimeout(service.getClientTimeout());
        result.setSerializeType(service.getSerializeType());
        result.setMessage(service.isMessage());
        result.setSharedThreadPool(service.isSharedThreadPool());
        return result;
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

                ServiceEndpointData endpoint = new ServiceEndpointData();
                endpoint.setConnectorName(connector.name());
                endpoint.setTransport(connector.listenType());
                endpoint.setProtocolName(protocol.name());
                endpoint.setProtocol(protocol.protocol());
                endpoint.setScheme(protocol.scheme());
                endpoint.setContextPath(protocol.option("contextPath", null));
                endpoint.setBindEnabled(connector.bindEnabled());
                endpoint.setConfiguredAddress(connector.address().toString());
                InterAddress listen = connector.bindEnabled() ? this.context.bindAddress(protocol.name()) : null;
                endpoint.setListenAddress(listen == null ? null : listen.toString());
                result.add(endpoint);
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

    private static Map<String, String> options(MetOptionSet options) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : options.getOptionKeys()) {
            result.put(key, options.getOption(key));
        }
        return result;
    }
}
