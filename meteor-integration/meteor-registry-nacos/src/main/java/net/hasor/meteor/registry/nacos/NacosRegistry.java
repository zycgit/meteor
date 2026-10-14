/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.DirectoryFilter;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 应用和服务定义持久保存；实例、成员关系和实例治理随 Naming 临时登记退出。 */
final class NacosRegistry implements StorageAccess {
    private final    NamingService                            naming;
    private final    ConfigService                            configs;
    private final    String                                   group;
    private final    NacosConfigStore                         documents;
    private final    NacosNamingStore                         runtime;
    private final    NacosGovernance                          governance;
    private final    NacosRuleStore                           rules;
    private final    Map<String, EntInstanceRuntimeData>      instances  = new HashMap<>();
    private final    Map<String, Map<String, MetServiceType>> references = new HashMap<>();
    private final    Set<NacosStorageWatch>                   watches    = ConcurrentHashMap.newKeySet();
    private final    ScheduledExecutorService                 executor;
    private volatile boolean                                  closed;

    NacosRegistry(NamingService naming, ConfigService configs, Properties properties, String group, long timeout) {
        this.naming = naming;
        this.configs = configs;
        this.group = group;
        this.documents = new NacosConfigStore(configs, group, timeout);
        this.runtime = new NacosNamingStore(naming, group);
        this.governance = new NacosGovernance(this.documents, this.runtime, properties, group);
        this.rules = new NacosRuleStore(this.documents, this.governance);
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Meteor-nacos-watch");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Registry storage is closed");
        }
    }

    private static String membersKey(String serviceId, MetServiceType role) {
        String suffix = role == MetServiceType.Provider ? ".providers" : ".consumers";
        return NacosKeyUtils.service(serviceId) + suffix;
    }

    @Override
    public EntApplicationCatalogData getApplication(String name) throws Exception {
        this.requireOpen();
        return this.documents.get(NacosKeyUtils.application(name), EntApplicationCatalogData.class);
    }

    @Override
    public void saveApplication(EntApplicationCatalogData value) throws Exception {
        this.requireOpen();
        String name = value.getApplicationName();
        String key = NacosKeyUtils.application(name);
        this.documents.update(key, EntApplicationCatalogData.class, previous -> value);
        this.documents.index("applications", name, key);
    }

    @Override
    public EntServiceCatalogData getService(String id) throws Exception {
        this.requireOpen();
        return this.documents.get(NacosKeyUtils.service(id), EntServiceCatalogData.class);
    }

    @Override
    public void saveService(EntServiceCatalogData value) throws Exception {
        this.requireOpen();
        String id = value.getServiceId();
        String key = NacosKeyUtils.service(id);
        // 服务登记仅合并声明字段，保留同一详情中的管理端规则开关。
        Map<String, Object> declaration = RegistryJson.copy(value, Map.class);
        this.documents.update(key, Map.class, previous -> {
            Map<String, Object> document = previous == null ? new LinkedHashMap<>() : previous;
            for (String field : List.of("serviceId", "group", "name", "version", "type")) {
                Object item = declaration.get(field);
                if (item != null) {
                    document.put(field, item);
                }
            }
            return document;
        });
        this.documents.index("services", id, key);
    }

    @Override
    public RecordPage<EntApplicationCatalogData> applications(DirectoryQuery query) throws Exception {
        this.requireOpen();
        Map<String, EntApplicationCatalogData> values = new TreeMap<>();
        for (String id : this.documents.references("applications").keySet()) {
            EntApplicationCatalogData value = this.getApplication(id);
            if (value != null) {
                values.put(id, value);
            }
        }
        return page(values, query, EntApplicationCatalogData::getApplicationName, value -> null);
    }

    @Override
    public RecordPage<EntServiceCatalogData> services(DirectoryQuery query) throws Exception {
        this.requireOpen();
        Map<String, EntServiceCatalogData> values = new TreeMap<>();
        for (String id : this.documents.references("services").keySet()) {
            EntServiceCatalogData value = this.getService(id);
            if (value != null) {
                values.put(id, value);
            }
        }
        return page(values, query, EntServiceCatalogData::getServiceId, value -> null);
    }

    @Override
    public InstanceStatus getInstance(String id) throws Exception {
        this.requireOpen();
        Instance node = this.runtime.find(NacosKeyUtils.instance(id), id);
        if (node == null) {
            return null;
        }
        EntInstanceRuntimeData body = RegistryJson.read(node.getMetadata().get("body"), EntInstanceRuntimeData.class);
        InstanceStatus result = new InstanceStatus();
        result.setInstance(RegistryJson.copy(body, EntInstanceCatalogData.class));
        result.setRuntime(body);
        return result;
    }

    @Override
    public RecordPage<InstanceStatus> instances(String applicationName, DirectoryQuery query) throws Exception {
        this.requireOpen();
        Set<String> names = new TreeSet<>();
        if (applicationName != null) {
            names.add(applicationName);
        } else {
            names.addAll(this.documents.references("applications").keySet());
        }
        Map<String, InstanceStatus> values = new TreeMap<>();
        for (String name : names) {
            String key = NacosKeyUtils.application(name) + ".instances";
            for (Instance reference : this.runtime.members(key)) {
                String id = reference.getMetadata().get("instanceId");
                InstanceStatus instance = this.getInstance(id);
                if (instance != null) {
                    values.put(id, instance);
                }
            }
        }
        return page(values, query, value -> identity(value.getInstance()), value -> value.getInstance().getUnitName());
    }

    @Override
    public synchronized void registerInstance(EntInstanceRuntimeData value) throws Exception {
        this.requireOpen();
        String id = value.getInstanceId();
        EntInstanceRuntimeData previous = this.instances.get(id);
        if (previous == null && this.getInstance(id) != null) {
            throw new IllegalStateException("Instance ID is already registered: " + id);
        }
        EntApplicationCatalogData application = new EntApplicationCatalogData();
        application.setApplicationName(value.getApplicationName());
        this.saveApplication(application);

        // 只持有本进程的运行快照；实例 ID 标识一次运行，不支持另一个进程接管相同 ID。
        EntInstanceRuntimeData snapshot = RegistryJson.copy(value, EntInstanceRuntimeData.class);
        this.instances.put(id, snapshot);
        this.references.computeIfAbsent(id, ignored -> new TreeMap<>());
        this.publishInstance(snapshot);
        this.ensureGovernance(snapshot, null);
        String applicationKey = NacosKeyUtils.application(value.getApplicationName()) + ".instances";
        this.runtime.publish(applicationKey, snapshot, Map.of());
        if (previous != null && !StringUtils.equals(previous.getApplicationName(), value.getApplicationName())) {
            String oldKey = NacosKeyUtils.application(previous.getApplicationName()) + ".instances";
            this.runtime.withdraw(oldKey, id);
        }
    }

    private void publishInstance(EntInstanceRuntimeData instance) throws Exception {
        String id = instance.getInstanceId();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("body", RegistryJson.write(instance));
        metadata.put("services", RegistryJson.write(this.references.get(id)));
        this.runtime.publish(NacosKeyUtils.instance(id), instance, metadata);
    }

    private void ensureGovernance(EntInstanceRuntimeData instance, String serviceId) throws Exception {
        String key = NacosKeyUtils.governance(instance.getInstanceId(), serviceId);
        // 客户端只创建空的控制节点。控制字段由 Naming 管理接口维护，重复上报不能覆盖它们。
        if (!this.runtime.owns(key, instance.getInstanceId())) {
            this.runtime.publish(key, instance, Map.of());
        }
    }

    private EntInstanceRuntimeData ownInstance(String id) {
        this.requireOpen();
        EntInstanceRuntimeData instance = this.instances.get(id);
        if (instance == null) {
            throw new IllegalStateException("Instance is not registered by this client: " + id);
        }
        return instance;
    }

    @Override
    public synchronized void registerService(String instanceId, EntServiceRuntimeData value) throws Exception {
        EntInstanceRuntimeData instance = this.ownInstance(instanceId);
        String id = value.getServiceId();
        this.saveService(value);
        this.ensureGovernance(instance, id);

        // 关系只保留自身的运行属性；服务声明、实例资料按各自的独立键查询。
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("enabled", value.isEnabled());
        relation.put("endpoints", value.getEndpoints());
        relation.put("addresses", value.getAddresses());
        relation.put("usedProtocols", value.getUsedProtocols());
        String body = RegistryJson.write(relation);
        this.runtime.publish(membersKey(id, value.getRole()), instance, Map.of("body", body));
        MetServiceType other = MetServiceType.Provider;
        if (value.getRole() == MetServiceType.Provider) {
            other = MetServiceType.Consumer;
        }
        String previousKey = membersKey(id, other);
        if (this.runtime.owns(previousKey, instanceId)) {
            String targetKey = membersKey(id, value.getRole());
            long deadline = NacosGovernance.writeDeadline();
            this.governance.locked(NacosKeyUtils.governance(instanceId, id), () -> {
                // 搬迁开关和撤回旧角色在同一治理锁内，避免期间下发到旧关系后丢失。
                this.rules.moveControls(instanceId, previousKey, targetKey, deadline);
                this.runtime.withdraw(previousKey, instanceId);
                return null;
            });
        }

        // 主关系成功后再发布反向引用。失败向上传递，重复登记会继续补齐引用。
        this.references.get(instanceId).put(id, value.getRole());
        this.publishInstance(instance);
    }

    @Override
    public synchronized void unregisterService(String instanceId, String serviceId) throws Exception {
        EntInstanceRuntimeData instance = this.ownInstance(instanceId);
        this.runtime.withdraw(membersKey(serviceId, MetServiceType.Provider), instanceId);
        this.runtime.withdraw(membersKey(serviceId, MetServiceType.Consumer), instanceId);
        this.runtime.withdraw(NacosKeyUtils.governance(instanceId, serviceId), instanceId);
        this.references.get(instanceId).remove(serviceId);
        this.publishInstance(instance);
    }

    @Override
    public RefServiceRelationData getMember(String serviceId, MetServiceType role, String instanceId) throws Exception {
        this.requireOpen();
        Instance node = this.runtime.find(membersKey(serviceId, role), instanceId);
        return node == null ? null : this.member(serviceId, role, node);
    }

    private RefServiceRelationData member(String serviceId, MetServiceType role, Instance node) throws Exception {
        InstanceStatus instance = this.getInstance(node.getMetadata().get("instanceId"));
        EntServiceCatalogData catalog = this.getService(serviceId);
        if (instance == null || catalog == null) {
            return null;
        }
        EntServiceRuntimeData value = RegistryJson.read(node.getMetadata().get("body"), EntServiceRuntimeData.class);
        value.setServiceId(serviceId);
        value.setGroup(catalog.getGroup());
        value.setName(catalog.getName());
        value.setVersion(catalog.getVersion());
        value.setType(catalog.getType());
        value.setRole(role);
        RefServiceRelationData result = new RefServiceRelationData();
        result.setInstance(instance.getRuntime());
        result.setService(value);
        return result;
    }

    @Override
    public EntServiceRuntimeData getInstanceService(String instanceId, String serviceId) throws Exception {
        this.requireOpen();
        for (MetServiceType role : MetServiceType.values()) {
            RefServiceRelationData relation = this.getMember(serviceId, role, instanceId);
            if (relation != null) {
                return relation.getService();
            }
        }
        return null;
    }

    @Override
    public RecordPage<EntServiceRuntimeData> instanceServices(String instanceId, DirectoryQuery query) throws Exception {
        this.requireOpen();
        Map<String, EntServiceRuntimeData> values = new TreeMap<>();
        Instance node = this.runtime.find(NacosKeyUtils.instance(instanceId), instanceId);
        if (node != null) {
            Map<?, ?> services = RegistryJson.read(node.getMetadata().get("services"), Map.class);
            for (Object id : services.keySet()) {
                String serviceId = (String) id;
                EntServiceRuntimeData value = this.getInstanceService(instanceId, serviceId);
                if (value != null) {
                    values.put(serviceId, value);
                }
            }
        }
        return page(values, query, EntServiceCatalogData::getServiceId, value -> null);
    }

    @Override
    public RecordPage<RefServiceRelationData> members(String serviceId, MetServiceType role, DirectoryQuery query) throws Exception {
        this.requireOpen();
        Map<String, RefServiceRelationData> values = new TreeMap<>();
        for (Instance node : this.runtime.members(membersKey(serviceId, role))) {
            RefServiceRelationData member = this.member(serviceId, role, node);
            if (member != null) {
                values.put(member.getInstance().getInstanceId(), member);
            }
        }
        return page(values, query, value -> identity(value.getInstance()), value -> value.getInstance().getUnitName());
    }

    @Override
    public String[] applicationUnits(String applicationName) throws Exception {
        this.requireOpen();
        Set<String> units = new TreeSet<>();
        String key = NacosKeyUtils.application(applicationName) + ".instances";
        for (Instance node : this.runtime.members(key)) {
            InstanceStatus instance = this.getInstance(node.getMetadata().get("instanceId"));
            if (instance != null && StringUtils.isNotBlank(instance.getInstance().getUnitName())) {
                units.add(instance.getInstance().getUnitName());
            }
        }
        return units.toArray(String[]::new);
    }

    @Override
    public String[] serviceUnits(String serviceId) throws Exception {
        this.requireOpen();
        Set<String> units = new TreeSet<>();
        for (MetServiceType role : MetServiceType.values()) {
            for (Instance node : this.runtime.members(membersKey(serviceId, role))) {
                InstanceStatus instance = this.getInstance(node.getMetadata().get("instanceId"));
                if (instance != null && StringUtils.isNotBlank(instance.getInstance().getUnitName())) {
                    units.add(instance.getInstance().getUnitName());
                }
            }
        }
        return units.toArray(String[]::new);
    }

    @Override
    public Map<String, ServiceSummary> serviceSummaries(Collection<String> serviceIds) throws Exception {
        this.requireOpen();
        Map<String, ServiceSummary> result = new HashMap<>();
        for (String id : serviceIds) {
            ServiceSummary summary = new ServiceSummary();
            Set<String> protocols = new TreeSet<>();
            for (MetServiceType role : MetServiceType.values()) {
                int count = 0;
                for (Instance node : this.runtime.members(membersKey(id, role))) {
                    RefServiceRelationData member = this.member(id, role, node);
                    if (member != null) {
                        count++;
                        protocols.addAll(DirectoryFilter.protocolNames(member.getService()));
                    }
                }
                if (role == MetServiceType.Provider) {
                    summary.setProviderCount(count);
                } else {
                    summary.setConsumerCount(count);
                }
            }
            summary.setProtocols(protocols.toArray(String[]::new));
            result.put(id, summary);
        }
        return result;
    }

    private static String identity(EntInstanceCatalogData value) {
        return value.getInstanceId() + " " + value.getIpAddress() + " " + value.getProcessId();
    }

    private static <T> RecordPage<T> page(Map<String, T> values, DirectoryQuery input, Function<T, String> text, Function<T, String> unit) {
        DirectoryQuery query = input == null ? new DirectoryQuery() : input;
        if (query.getLimit() < 1 || query.getLimit() > 1000) {
            throw new IllegalArgumentException("Directory page limit must be between 1 and 1000");
        }
        RecordPage<T> page = new RecordPage<>();
        String last = null;
        for (Map.Entry<String, T> entry : values.entrySet()) {
            String id = entry.getKey();
            if (query.getCursor() != null && id.compareTo(query.getCursor()) <= 0) {
                continue;
            }
            T value = entry.getValue();
            if (!DirectoryFilter.protocol(value, query.getProtocol())) {
                continue;
            }
            String selectedUnit = query.getUnitName();
            if (StringUtils.isNotBlank(selectedUnit) && !StringUtils.equals(selectedUnit, unit.apply(value))) {
                continue;
            }
            if (StringUtils.isNotBlank(query.getSearch())) {
                String candidate = DirectoryFilter.text(value, query.getField(), text.apply(value));
                String search = query.getSearch().toLowerCase(Locale.ROOT);
                if (!candidate.toLowerCase(Locale.ROOT).contains(search)) {
                    continue;
                }
            }
            if (page.getItems().size() == query.getLimit()) {
                page.setNextCursor(last);
                break;
            }
            page.getItems().add(value);
            last = id;
        }
        return page;
    }

    @Override
    public EntityRecord<EntConfigurationData> getConfiguration(String instanceId, String serviceId) throws Exception {
        this.requireOpen();
        return this.governance.get(instanceId, serviceId, "state", EntConfigurationData.class);
    }

    @Override
    public void saveConfiguration(String instanceId, String serviceId, EntityRecord<EntConfigurationData> value) throws Exception {
        this.requireOpen();
        this.governance.save(instanceId, serviceId, "state", value, false);
    }

    @Override
    public boolean deleteConfiguration(String instanceId, String serviceId, String revision) throws Exception {
        this.requireOpen();
        EntityRecord<EntConfigurationData> value = new EntityRecord<>();
        value.setRevision(revision);
        return this.governance.save(instanceId, serviceId, "state", value, true);
    }

    @Override
    public EntityRecord<EntRuleConfigurationData> getRule(String instanceId, String serviceId, RuleKind kind) throws Exception {
        this.requireOpen();
        return this.rules.get(instanceId, serviceId, kind);
    }

    @Override
    public void saveRule(String instanceId, String serviceId, RuleKind kind, EntityRecord<EntRuleConfigurationData> value) throws Exception {
        this.requireOpen();
        this.rules.save(instanceId, serviceId, kind, value, false);
    }

    @Override
    public boolean deleteRule(String instanceId, String serviceId, RuleKind kind, String revision) throws Exception {
        this.requireOpen();
        EntityRecord<EntRuleConfigurationData> value = new EntityRecord<>();
        value.setRevision(revision);
        return this.rules.save(instanceId, serviceId, kind, value, true);
    }

    @Override
    public EntityRecord<EntMetadataData> getMetadata(String instanceId, String serviceId) throws Exception {
        this.requireOpen();
        return this.governance.get(instanceId, serviceId, "metadata", EntMetadataData.class);
    }

    @Override
    public void saveMetadata(String instanceId, String serviceId, EntityRecord<EntMetadataData> value) throws Exception {
        this.requireOpen();
        this.governance.save(instanceId, serviceId, "metadata", value, false);
    }

    @Override
    public boolean deleteMetadata(String instanceId, String serviceId, String revision) throws Exception {
        this.requireOpen();
        EntityRecord<EntMetadataData> value = new EntityRecord<>();
        value.setRevision(revision);
        return this.governance.save(instanceId, serviceId, "metadata", value, true);
    }

    private synchronized Cancellable watch(List<String> names, List<String> dataIds, Callable<Map<String, String>> snapshot, Runnable listener) {
        this.requireOpen();
        NacosStorageWatch watch = new NacosStorageWatch(this.naming, this.configs, this.group, // 原生订阅来源
                names, dataIds, snapshot, listener, this.executor, this.watches::remove);
        this.watches.add(watch);
        watch.start();
        return watch;
    }

    @Override
    public Cancellable watchProviders(String serviceId, Runnable listener) {
        String key = membersKey(serviceId, MetServiceType.Provider);
        return this.watch(List.of(key), List.of(), () -> this.runtime.snapshot(key), listener);
    }

    @Override
    public Cancellable watchConfiguration(String instanceId, String serviceId, Runnable listener) {
        String key = NacosKeyUtils.governance(instanceId, serviceId);
        if (instanceId != null) {
            List<String> names = new ArrayList<>();
            names.add(key);
            if (serviceId == null) {
                names.add(NacosKeyUtils.instance(instanceId));
            } else {
                names.add(membersKey(serviceId, MetServiceType.Provider));
                names.add(membersKey(serviceId, MetServiceType.Consumer));
            }
            return this.watch(names, List.of(), () -> {
                Map<String, String> values = new TreeMap<>();
                for (String name : names) {
                    Map<String, String> fields = this.governance.readRuntime(name, instanceId, false);
                    if (fields == null) {
                        continue;
                    }
                    if (StringUtils.equals(name, key)) {
                        values.put(name, RegistryJson.write(fields));
                        continue;
                    }
                    // 应用重新上报端点或状态不属于治理变更，只比较详情中的规则开关。
                    Map<String, String> controls = new TreeMap<>();
                    for (String field : List.of("ruleEnabled", "ruleCleared")) {
                        if (fields.containsKey(field)) {
                            controls.put(field, fields.get(field));
                        }
                    }
                    values.put(name, RegistryJson.write(controls));
                }
                return values;
            }, listener);
        }
        List<String> keys = new ArrayList<>();
        keys.add(key + ".state");
        keys.add(key + ".metadata");
        if (serviceId != null) {
            keys.add(NacosKeyUtils.service(serviceId));
        }
        for (RuleKind kind : RuleKind.values()) {
            keys.add(key + ".rule." + kind.name());
        }
        return this.watch(List.of(), keys, () -> {
            Map<String, String> values = new TreeMap<>();
            for (String dataId : keys) {
                String body = this.documents.read(dataId);
                if (body != null) {
                    values.put(dataId, body);
                }
            }
            return values;
        }, listener);
    }

    @Override
    public synchronized void close() throws Exception {
        if (this.closed) {
            return;
        }
        this.closed = true;
        Exception failure = null;
        for (NacosStorageWatch watch : new ArrayList<>(this.watches)) {
            try {
                watch.cancel();
            } catch (Exception cleanup) {
                failure = collect(failure, cleanup);
            }
        }
        this.executor.shutdownNow();
        // 撤回失败也必须关闭 SDK 连接，让服务端最终移除临时登记。
        List<AutoCloseable> resources = List.of(this.runtime::close, this.governance, // 先撤回登记和释放治理客户端
                this.naming::shutDown, this.configs::shutDown);
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception cleanup) {
                failure = collect(failure, cleanup);
            }
        }
        this.instances.clear();
        this.references.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private static Exception collect(Exception previous, Exception next) {
        if (previous == null) {
            return next;
        }
        previous.addSuppressed(next);
        return previous;
    }
}
