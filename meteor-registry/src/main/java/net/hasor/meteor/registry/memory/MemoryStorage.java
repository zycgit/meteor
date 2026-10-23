/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.memory;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.DirectoryFilter;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 内存业务存储会话；锁内只有内存操作，回调在锁外执行。 */
final class MemoryStorage implements StorageAccess {
    private final MemoryRegistryDatabase   database;
    private final String                   sessionId           = UUID.randomUUID().toString();
    private final Set<String>              registeredInstances = new HashSet<>();
    private       ScheduledExecutorService watches;
    private       boolean                  closed;

    public MemoryStorage(MemoryRegistryDatabase database) {
        this.database = database;
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Registry storage is closed");
        }
    }

    private static String required(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("Registry identity must not be blank");
        } else {
            return id;
        }
    }

    @Override
    public String[] applicationUnits(String applicationName) {
        synchronized (this.database) {
            this.requireOpen();
            Set<String> units = new TreeSet<>();
            Map<String, EntInstanceCatalogData> entries = this.database.applicationInstances.get(required(applicationName));
            if (entries != null) {
                for (EntInstanceCatalogData instance : entries.values()) {
                    if (StringUtils.isNotBlank(instance.getUnitName())) {
                        units.add(instance.getUnitName());
                    }
                }
            }
            return units.toArray(String[]::new);
        }
    }

    @Override
    public String[] serviceUnits(String serviceId) {
        synchronized (this.database) {
            this.requireOpen();
            Set<String> units = new TreeSet<>();
            for (MetServiceType role : MetServiceType.values()) {
                Map<String, RefServiceRelationData> members = this.memberDirectory(serviceId, role);
                if (members != null) {
                    for (RefServiceRelationData member : members.values()) {
                        String unit = member.getInstance().getUnitName();
                        if (StringUtils.isNotBlank(unit)) {
                            units.add(unit);
                        }
                    }
                }
            }
            return units.toArray(String[]::new);
        }
    }

    @Override
    public EntApplicationCatalogData getApplication(String applicationName) {
        synchronized (this.database) {
            this.requireOpen();
            return copy(this.database.applications.get(required(applicationName)), EntApplicationCatalogData.class);
        }
    }

    @Override
    public void saveApplication(EntApplicationCatalogData application) {
        synchronized (this.database) {
            this.requireOpen();
            String name = required(application.getApplicationName());
            this.database.applications.put(name, copy(application, EntApplicationCatalogData.class));
        }
    }

    @Override
    public void saveService(EntServiceCatalogData service) {
        synchronized (this.database) {
            this.requireOpen();
            String id = required(service.getServiceId());
            EntServiceCatalogData merged = copy(service, EntServiceCatalogData.class);
            EntServiceCatalogData previous = this.database.services.get(id);
            if (previous != null) {
                if (merged.getName() == null) {
                    merged.setName(previous.getName());
                }
                if (merged.getType() == null) {
                    merged.setType(previous.getType());
                }
                if (merged.getGroup() == null) {
                    merged.setGroup(previous.getGroup());
                }
                if (merged.getVersion() == null) {
                    merged.setVersion(previous.getVersion());
                }
            }
            this.database.services.put(id, merged);
        }
    }

    @Override
    public RecordPage<EntApplicationCatalogData> applications(DirectoryQuery query) {
        synchronized (this.database) {
            this.requireOpen();
            return page(this.database.applications, query, EntApplicationCatalogData::getApplicationName, value -> null, value -> copy(value, EntApplicationCatalogData.class));
        }
    }

    @Override
    public InstanceStatus getInstance(String instanceId) {
        synchronized (this.database) {
            this.requireOpen();
            EntInstanceCatalogData instance = this.database.instances.get(required(instanceId));
            return instance == null ? null : this.status(instance);
        }
    }

    private InstanceStatus status(EntInstanceCatalogData instance) {
        InstanceStatus result = new InstanceStatus();
        result.setInstance(copy(instance, EntInstanceCatalogData.class));
        result.setRuntime(copy(this.database.runtime.get(instance.getInstanceId()), EntInstanceRuntimeData.class));
        return result;
    }

    @Override
    public RecordPage<InstanceStatus> instances(String applicationName, DirectoryQuery query) {
        synchronized (this.database) {
            this.requireOpen();
            NavigableMap<String, EntInstanceCatalogData> directory = this.database.instances;
            if (applicationName != null) {
                directory = this.database.applicationInstances.get(required(applicationName));
            }

            return page(directory, query, MemoryStorage::identity, EntInstanceCatalogData::getUnitName, this::status);
        }
    }

    private static String identity(EntInstanceCatalogData instance) {
        return instance.getInstanceId() + " " + instance.getIpAddress() + " " + instance.getProcessId();
    }

    @Override
    public EntServiceCatalogData getService(String serviceId) {
        synchronized (this.database) {
            this.requireOpen();
            return copy(this.database.services.get(required(serviceId)), EntServiceCatalogData.class);
        }
    }

    @Override
    public RecordPage<EntServiceCatalogData> services(DirectoryQuery query) {
        synchronized (this.database) {
            this.requireOpen();
            return page(this.database.services, query, EntServiceCatalogData::getServiceId, value -> null, value -> copy(value, EntServiceCatalogData.class));
        }
    }

    @Override
    public Map<String, ServiceSummary> serviceSummaries(Collection<String> serviceIds) {
        synchronized (this.database) {
            this.requireOpen();
            Map<String, ServiceSummary> result = new HashMap<>();
            for (String id : serviceIds) {
                ServiceSummary summary = this.database.summaries.get(required(id));
                if (summary != null) {
                    result.put(id, copy(summary, ServiceSummary.class));
                }
            }
            return result;
        }
    }

    private void updateSummary(String serviceId) {
        ServiceSummary summary = new ServiceSummary();
        Set<String> protocols = new TreeSet<>();
        Map<MetServiceType, NavigableMap<String, RefServiceRelationData>> roles = this.database.members.get(serviceId);
        if (roles != null) {
            for (Map.Entry<MetServiceType, NavigableMap<String, RefServiceRelationData>> role : roles.entrySet()) {
                if (role.getKey() == MetServiceType.Provider) {
                    summary.setProviderCount(role.getValue().size());
                } else {
                    summary.setConsumerCount(role.getValue().size());
                }
                for (RefServiceRelationData member : role.getValue().values()) {
                    protocols.addAll(DirectoryFilter.protocolNames(member.getService()));
                }
            }
        }

        summary.setProtocols(protocols.toArray(String[]::new));
        this.database.summaries.put(serviceId, summary);
    }

    @Override
    public EntServiceRuntimeData getInstanceService(String instanceId, String serviceId) {
        synchronized (this.database) {
            this.requireOpen();
            NavigableMap<String, EntServiceRuntimeData> entries = this.database.instanceServices.get(required(instanceId));
            required(serviceId);
            return entries == null ? null : copy(entries.get(serviceId), EntServiceRuntimeData.class);
        }
    }

    @Override
    public RecordPage<EntServiceRuntimeData> instanceServices(String instanceId, DirectoryQuery query) {
        synchronized (this.database) {
            this.requireOpen();
            return page(this.database.instanceServices.get(required(instanceId)), query, EntServiceCatalogData::getServiceId, value -> null, value -> copy(value, EntServiceRuntimeData.class));
        }
    }

    private NavigableMap<String, RefServiceRelationData> memberDirectory(String serviceId, MetServiceType role) {
        required(serviceId);
        Objects.requireNonNull(role, "Service role is required");
        Map<MetServiceType, NavigableMap<String, RefServiceRelationData>> service = this.database.members.get(serviceId);
        return service == null ? null : service.get(role);
    }

    @Override
    public RefServiceRelationData getMember(String serviceId, MetServiceType role, String instanceId) {
        synchronized (this.database) {
            this.requireOpen();
            NavigableMap<String, RefServiceRelationData> members = this.memberDirectory(serviceId, role);
            required(instanceId);
            return members == null ? null : copy(members.get(instanceId), RefServiceRelationData.class);
        }
    }

    @Override
    public RecordPage<RefServiceRelationData> members(String serviceId, MetServiceType role, DirectoryQuery query) {
        synchronized (this.database) {
            this.requireOpen();
            return page(this.memberDirectory(serviceId, role), query, value -> identity(value.getInstance()), value -> value.getInstance().getUnitName(), value -> copy(value, RefServiceRelationData.class));
        }
    }

    @Override
    public void registerInstance(EntInstanceRuntimeData instance) {
        String id = required(instance.getInstanceId());
        String application = required(instance.getApplicationName());
        synchronized (this.database) {
            this.requireOpen();
            String owner = this.database.sessions.get(id);
            // 一旦本会话登记过该实例，发现被新会话接管后禁止重新抢占。
            if (this.registeredInstances.contains(id) && !StringUtils.equals(owner, this.sessionId)) {
                throw new IllegalStateException("Instance registration was replaced by a newer session: " + id);
            }

            if (owner != null && !StringUtils.equals(owner, this.sessionId)) {
                this.removeCurrentServices(id);
            }

            EntInstanceRuntimeData reported = this.database.runtime.get(id);
            if (StringUtils.equals(owner, this.sessionId) && RegistryJson.sameData(reported, instance)) {
                return;
            }

            EntInstanceCatalogData previous = this.database.instances.get(id);
            if (previous != null && !StringUtils.equals(application, previous.getApplicationName())) {
                this.database.applicationInstances.get(previous.getApplicationName()).remove(id);
            }

            EntApplicationCatalogData app = new EntApplicationCatalogData();
            app.setApplicationName(application);
            this.database.applications.putIfAbsent(application, app);
            EntInstanceCatalogData catalog = copy(instance, EntInstanceCatalogData.class);
            this.database.instances.put(id, catalog);
            this.database.applicationInstances.computeIfAbsent(application, key -> new TreeMap<>()).put(id, catalog);
            this.database.runtime.put(id, copy(instance, EntInstanceRuntimeData.class));
            this.database.sessions.put(id, this.sessionId);
            this.registeredInstances.add(id);

            // 身份变化只传播到本实例已经登记的服务，不遍历其他实例。
            NavigableMap<String, EntServiceRuntimeData> services = this.database.instanceServices.get(id);
            if (services != null) {
                for (EntServiceRuntimeData service : services.values()) {
                    this.putMember(instance, service);
                }
            }
        }
    }

    private EntInstanceRuntimeData requireOwner(String instanceId) {
        required(instanceId);
        if (!StringUtils.equals(this.database.sessions.get(instanceId), this.sessionId)) {
            throw new IllegalStateException("Instance is not registered by this session: " + instanceId);
        } else {
            return this.database.runtime.get(instanceId);
        }
    }

    @Override
    public void registerService(String instanceId, EntServiceRuntimeData service) {
        String serviceId = required(service.getServiceId());
        Objects.requireNonNull(service.getRole(), "Service role is required");
        synchronized (this.database) {
            this.requireOpen();
            EntInstanceRuntimeData instance = this.requireOwner(instanceId);
            this.saveService(service);

            NavigableMap<String, EntServiceRuntimeData> entries = this.database.instanceServices.computeIfAbsent(instanceId, key -> new TreeMap<>());
            EntServiceRuntimeData previous = entries.get(serviceId);
            if (previous != null && RegistryJson.sameData(previous, service)) {
                return;
            }

            if (previous != null && previous.getRole() != service.getRole()) {
                this.memberDirectory(serviceId, previous.getRole()).remove(instanceId);
            }

            entries.put(serviceId, copy(service, EntServiceRuntimeData.class));
            this.putMember(instance, service);
        }
    }

    private void putMember(EntInstanceRuntimeData instance, EntServiceRuntimeData service) {
        RefServiceRelationData member = new RefServiceRelationData();
        member.setInstance(copy(instance, EntInstanceRuntimeData.class));
        member.setService(copy(service, EntServiceRuntimeData.class));
        Map<MetServiceType, NavigableMap<String, RefServiceRelationData>> roles = this.database.members.computeIfAbsent(service.getServiceId(), key -> new EnumMap<>(MetServiceType.class));
        roles.computeIfAbsent(service.getRole(), key -> new TreeMap<>()).put(instance.getInstanceId(), member);
        this.updateSummary(service.getServiceId());
        this.database.changed("providers/" + service.getServiceId());
    }

    @Override
    public void unregisterService(String instanceId, String serviceId) {
        required(serviceId);
        synchronized (this.database) {
            this.requireOpen();
            this.requireOwner(instanceId);
            NavigableMap<String, EntServiceRuntimeData> entries = this.database.instanceServices.get(instanceId);
            if (entries == null) {
                return;
            }

            EntServiceRuntimeData removed = entries.remove(serviceId);
            if (removed != null) {
                this.memberDirectory(serviceId, removed.getRole()).remove(instanceId);
                this.updateSummary(serviceId);
                this.database.changed("providers/" + serviceId);
            }
        }
    }

    private void removeCurrentServices(String instanceId) {
        NavigableMap<String, EntServiceRuntimeData> entries = this.database.instanceServices.remove(instanceId);
        if (entries != null) {
            for (EntServiceRuntimeData service : entries.values()) {
                this.memberDirectory(service.getServiceId(), service.getRole()).remove(instanceId);
                this.updateSummary(service.getServiceId());
                this.database.changed("providers/" + service.getServiceId());
            }
        }
    }

    private static String scope(String instanceId, String serviceId) {
        String instance = "";
        String service = "";
        if (instanceId != null) {
            instance = encode(required(instanceId));
        }
        if (serviceId != null) {
            service = encode(required(serviceId));
        }
        return "configuration/" + instance + "/" + service;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private <T> EntityRecord<T> readConfiguration(String scope, String item, Class<T> type) {
        synchronized (this.database) {
            this.requireOpen();
            EntityRecord<?> stored = this.database.configurations.get(scope + "/" + item);
            if (stored == null) {
                return null;
            }

            EntityRecord<T> result = new EntityRecord<>();
            result.setData(copy(stored.getData(), type));
            result.setRevision(stored.getRevision());
            return result;
        }
    }

    private <T> void writeConfiguration(String scope, String item, EntityRecord<T> value, Class<T> type) {
        Objects.requireNonNull(value.getData(), "Configuration data is required");
        synchronized (this.database) {
            this.requireOpen();
            String key = scope + "/" + item;
            EntityRecord<?> previous = this.database.configurations.get(key);
            String revision = previous == null ? null : previous.getRevision();
            if (!StringUtils.equals(revision, value.getRevision())) {
                throw new ConcurrentModificationException("Configuration has changed: " + key);
            }
            if (previous != null && RegistryJson.sameData(previous.getData(), value.getData())) {
                return;
            }

            EntityRecord<T> next = new EntityRecord<>();
            next.setData(copy(value.getData(), type));
            next.setRevision(UUID.randomUUID().toString());
            this.database.configurations.put(key, next);
            this.database.changed(scope);
        }
    }

    @Override
    public EntityRecord<EntConfigurationData> getConfiguration(String instanceId, String serviceId) {
        return this.readConfiguration(scope(instanceId, serviceId), "state", EntConfigurationData.class);
    }

    @Override
    public void saveConfiguration(String instanceId, String serviceId, EntityRecord<EntConfigurationData> value) {
        this.writeConfiguration(scope(instanceId, serviceId), "state", value, EntConfigurationData.class);
    }

    @Override
    public EntityRecord<EntRuleConfigurationData> getRule(String instanceId, String serviceId, RuleKind kind) {
        return this.readConfiguration(scope(instanceId, serviceId), "rules/" + Objects.requireNonNull(kind), EntRuleConfigurationData.class);
    }

    @Override
    public void saveRule(String instanceId, String serviceId, RuleKind kind, EntityRecord<EntRuleConfigurationData> value) {
        this.writeConfiguration(scope(instanceId, serviceId), "rules/" + Objects.requireNonNull(kind), value, EntRuleConfigurationData.class);
    }

    @Override
    public EntityRecord<EntMetadataData> getMetadata(String instanceId, String serviceId) {
        return this.readConfiguration(scope(instanceId, serviceId), "metadata", EntMetadataData.class);
    }

    @Override
    public void saveMetadata(String instanceId, String serviceId, EntityRecord<EntMetadataData> value) {
        this.writeConfiguration(scope(instanceId, serviceId), "metadata", value, EntMetadataData.class);
    }

    @Override
    public boolean deleteConfiguration(String instanceId, String serviceId, String revision) {
        return this.delete(scope(instanceId, serviceId), "state", revision);
    }

    @Override
    public boolean deleteRule(String instanceId, String serviceId, RuleKind kind, String revision) {
        return this.delete(scope(instanceId, serviceId), "rules/" + Objects.requireNonNull(kind), revision);
    }

    @Override
    public boolean deleteMetadata(String instanceId, String serviceId, String revision) {
        return this.delete(scope(instanceId, serviceId), "metadata", revision);
    }

    private boolean delete(String scope, String item, String revision) {
        synchronized (this.database) {
            this.requireOpen();
            String key = scope + "/" + item;
            EntityRecord<?> previous = this.database.configurations.get(key);
            if (previous == null) {
                return false;
            }
            if (!StringUtils.equals(revision, previous.getRevision())) {
                throw new ConcurrentModificationException("Configuration changed during deletion");
            }

            this.database.configurations.remove(key);
            this.database.changed(scope);
            return true;
        }
    }

    @Override
    public Cancellable watchProviders(String serviceId, Runnable listener) {
        return this.watch("providers/" + required(serviceId), listener);
    }

    @Override
    public Cancellable watchConfiguration(String instanceId, String serviceId, Runnable listener) {
        return this.watch(scope(instanceId, serviceId), listener);
    }

    private Cancellable watch(String scope, Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (this.database) {
            this.requireOpen();
            if (this.watches == null) {
                ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                    Thread thread = new Thread(task, "Meteor-memory-watch-" + this.sessionId);
                    thread.setDaemon(true);
                    return thread;
                });

                executor.setRemoveOnCancelPolicy(true);
                this.watches = executor;
            }

            MemoryStorageWatch watch = new MemoryStorageWatch(() -> {
                synchronized (this.database) {
                    this.requireOpen();
                    return Map.of(scope, String.valueOf(this.database.changes.getOrDefault(scope, 0L)));
                }
            }, listener);

            watch.scheduled(this.watches.scheduleWithFixedDelay(watch, 0, 100, TimeUnit.MILLISECONDS));
            return watch;
        }
    }

    @Override
    public void close() {
        synchronized (this.database) {
            if (this.closed) {
                return;
            }

            this.closed = true;
            if (this.watches != null) {
                this.watches.shutdownNow();
            }

            for (String id : this.registeredInstances) {
                if (StringUtils.equals(this.database.sessions.get(id), this.sessionId)) {
                    this.removeCurrentServices(id);
                    this.database.runtime.remove(id);
                    this.database.sessions.remove(id);
                }
            }
        }
    }

    private static <T> T copy(Object value, Class<T> type) {
        return value == null ? null : RegistryJson.copy(value, type);
    }

    private static <T, R> RecordPage<R> page(NavigableMap<String, T> directory, DirectoryQuery query, Function<T, String> searchable, Function<T, String> unit, Function<T, R> mapper) {
        Objects.requireNonNull(query, "query");
        int limit = query.getLimit();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Page limit must be between 1 and 1000");
        }

        RecordPage<R> page = new RecordPage<>();
        if (directory == null) {
            return page;
        }
        NavigableMap<String, T> remaining = directory;
        if (query.getCursor() != null) {
            remaining = directory.tailMap(query.getCursor(), false);
        }

        String last = null;
        for (Map.Entry<String, T> entry : remaining.entrySet()) {
            T value = entry.getValue();
            if (!DirectoryFilter.protocol(value, query.getProtocol())) {
                continue;
            }

            if (StringUtils.isNotBlank(query.getSearch())) {
                String candidate = DirectoryFilter.text(value, query.getField(), searchable.apply(value));
                String search = query.getSearch().toLowerCase(Locale.ROOT);
                if (!candidate.toLowerCase(Locale.ROOT).contains(search)) {
                    continue;
                }
            }

            if (query.getUnitName() != null && !StringUtils.equals(query.getUnitName(), unit.apply(value))) {
                continue;
            }

            if (page.getItems().size() == limit) {
                page.setNextCursor(last);
                break;
            }

            page.getItems().add(mapper.apply(value));
            last = entry.getKey();
        }
        return page;
    }
}
