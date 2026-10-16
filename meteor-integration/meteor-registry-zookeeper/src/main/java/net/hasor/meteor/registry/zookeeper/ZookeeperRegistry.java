/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.zookeeper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.function.EConsumer;
import net.hasor.cobble.function.EFunction;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.DirectoryFilter;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorOp;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

/** 持久业务目录与原生临时登记；成员正文和反向引用在同一 multi 事务提交。 */
final class ZookeeperRegistry implements StorageAccess {
    private final    CuratorFramework           client;
    private final    String                     root;
    private final    Map<String, Integer>       ownerVersions      = new HashMap<>();
    private final    Set<ZookeeperStorageWatch> watches            = ConcurrentHashMap.newKeySet();
    private final    ScheduledExecutorService   executor;
    private final    ZookeeperRuleStore         rules;
    private volatile boolean                    closed;
    private final    ConnectionStateListener    connectionListener = (connection, state) -> {
        this.watches.forEach(ZookeeperStorageWatch::refresh);
        if (state.isConnected()) {
            this.watches.forEach(ZookeeperStorageWatch::signal);
        }
    };

    ZookeeperRegistry(CuratorFramework client, String root) throws Exception {
        this.client = client;
        this.rules = new ZookeeperRuleStore(client);
        this.root = StringUtils.equals(root, "/") ? "" : root;
        this.parent(this.root + "/applications");
        this.parent(this.root + "/instances");
        this.parent(this.root + "/services");
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Meteor-zookeeper-watch");
            thread.setDaemon(true);
            return thread;
        });
        this.client.getConnectionStateListenable().addListener(this.connectionListener);
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Registry storage is closed");
        }
    }

    private static String key(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("Registry identity must not be blank");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    private String instance(String id) {
        return this.root + "/instances/" + key(id);
    }

    private String service(String id) {
        return this.root + "/services/" + key(id);
    }

    private String application(String name) {
        return this.root + "/applications/" + key(name);
    }

    private static String role(MetServiceType role) {
        Objects.requireNonNull(role, "Service role is required");
        return role == MetServiceType.Provider ? "providers" : "consumers";
    }

    private void parent(String path) throws Exception {
        this.requireOpen();
        try {
            this.client.create().creatingParentsIfNeeded().forPath(path);
        } catch (KeeperException.NodeExistsException ignored) {
            // 目录由并发登记者共同创建。
        }
    }

    private <T> T read(String path, Class<T> type) throws Exception {
        this.requireOpen();
        try {
            byte[] body = this.client.getData().forPath(path);
            return RegistryJson.read(new String(body, StandardCharsets.UTF_8), type);
        } catch (KeeperException.NoNodeException absent) {
            return null;
        }
    }

    private List<String> children(String path) throws Exception {
        this.requireOpen();
        try {
            List<String> result = new ArrayList<>(this.client.getChildren().forPath(path));
            Collections.sort(result);
            return result;
        } catch (KeeperException.NoNodeException absent) {
            return Collections.emptyList();
        }
    }

    private void put(List<CuratorOp> ops, String path, Object value, boolean temporary) throws Exception {
        this.parent(path.substring(0, path.lastIndexOf('/')));
        Stat stat = new Stat();
        byte[] body = RegistryJson.write(value).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] previous = this.client.getData().storingStatIn(stat).forPath(path);
            // 登记只覆盖应用字段，详情内由管理端维护的规则开关保持原值。
            if (value != null && !(value instanceof String)) {
                Map<String, Object> detail = RegistryJson.copy(value, Map.class);
                Map<?, ?> existing = RegistryJson.read(new String(previous, StandardCharsets.UTF_8), Map.class);
                if (existing != null) {
                    for (String field : List.of("ruleEnabled", "ruleCleared")) {
                        if (existing.containsKey(field)) {
                            detail.put(field, existing.get(field));
                        }
                    }
                }
                body = RegistryJson.write(detail).getBytes(StandardCharsets.UTF_8);
            }
            ops.add(this.client.transactionOp().setData().withVersion(stat.getVersion()).forPath(path, body));
        } catch (KeeperException.NoNodeException absent) {
            CreateMode mode = temporary ? CreateMode.EPHEMERAL : CreateMode.PERSISTENT;
            ops.add(this.client.transactionOp().create().withMode(mode).forPath(path, body));
        }
    }

    private void remove(List<CuratorOp> ops, String path) throws Exception {
        Stat stat = this.client.checkExists().forPath(path);
        if (stat != null) {
            ops.add(this.client.transactionOp().delete().withVersion(stat.getVersion()).forPath(path));
        }
    }

    private void mutate(EConsumer<List<CuratorOp>, Exception> mutation) throws Exception {
        this.requireOpen();
        for (int attempt = 0; attempt < 8; attempt++) {
            List<CuratorOp> ops = new ArrayList<>();
            try {
                mutation.eAccept(ops);
                if (!ops.isEmpty()) {
                    this.client.transaction().forOperations(ops);
                }
                return;
            } catch (KeeperException.BadVersionException | KeeperException.NodeExistsException | KeeperException.NoNodeException conflict) {
                if (attempt == 7) {
                    throw conflict;
                }
            }
        }
    }

    @Override
    public String[] applicationUnits(String applicationName) throws Exception {
        String path = this.application(applicationName) + "/instances";
        Set<String> units = new TreeSet<>();
        for (String child : this.children(path)) {
            EntInstanceCatalogData instance = this.read(path + "/" + child, EntInstanceCatalogData.class);
            if (instance != null && StringUtils.isNotBlank(instance.getUnitName())) {
                units.add(instance.getUnitName());
            }
        }
        return units.toArray(String[]::new);
    }

    @Override
    public String[] serviceUnits(String serviceId) throws Exception {
        Set<String> units = new TreeSet<>();
        for (MetServiceType role : MetServiceType.values()) {
            String path = this.service(serviceId) + "/" + role(role);
            for (String child : this.children(path)) {
                RefServiceRelationData member = this.memberAt(path + "/" + child, serviceId, role);
                if (member != null && StringUtils.isNotBlank(member.getInstance().getUnitName())) {
                    units.add(member.getInstance().getUnitName());
                }
            }
        }
        return units.toArray(String[]::new);
    }

    @Override
    public EntApplicationCatalogData getApplication(String name) throws Exception {
        return this.read(this.application(name) + "/info", EntApplicationCatalogData.class);
    }

    @Override
    public void saveApplication(EntApplicationCatalogData application) throws Exception {
        this.mutate(ops -> this.put(ops, this.application(application.getApplicationName()) + "/info", application, false));
    }

    private <T> RecordPage<T> page(String path, DirectoryQuery query, //
            EFunction<String, T, Exception> reader, Function<T, String> text, Function<T, String> unit) throws Exception {
        DirectoryQuery selection = query == null ? new DirectoryQuery() : query;
        if (selection.getLimit() < 1 || selection.getLimit() > 1000) {
            throw new IllegalArgumentException("Directory page limit must be between 1 and 1000");
        }
        RecordPage<T> page = new RecordPage<>();
        String last = null;
        // ZooKeeper 只枚举当前目录的子节点名；正文按页读取，不展开其它目录。
        for (String child : this.children(path)) {
            if (selection.getCursor() != null && child.compareTo(selection.getCursor()) <= 0) {
                continue;
            }
            T value = reader.eApply(child);
            if (value == null) {
                continue;
            }
            if (!DirectoryFilter.protocol(value, selection.getProtocol())) {
                continue;
            }
            if (StringUtils.isNotBlank(selection.getSearch())) {
                String candidate = DirectoryFilter.text(value, selection.getField(), text.apply(value));
                String search = selection.getSearch().toLowerCase(Locale.ROOT);
                if (!candidate.toLowerCase(Locale.ROOT).contains(search)) {
                    continue;
                }
            }
            if (StringUtils.isNotBlank(selection.getUnitName()) && !StringUtils.equals(selection.getUnitName(), unit.apply(value))) {
                continue;
            }
            if (page.getItems().size() == selection.getLimit()) {
                page.setNextCursor(last);
                break;
            }
            page.getItems().add(value);
            last = child;
        }
        return page;
    }

    @Override
    public RecordPage<EntApplicationCatalogData> applications(DirectoryQuery query) throws Exception {
        String path = this.root + "/applications";
        return this.page(path, query, child -> this.read(path + "/" + child + "/info", EntApplicationCatalogData.class), EntApplicationCatalogData::getApplicationName, value -> null);
    }

    private InstanceStatus status(String path, EntInstanceCatalogData archive) throws Exception {
        if (archive == null) {
            return null;
        }
        InstanceStatus result = new InstanceStatus();
        result.setInstance(archive);
        result.setRuntime(this.read(path + "/runtime", EntInstanceRuntimeData.class));
        return result;
    }

    @Override
    public InstanceStatus getInstance(String instanceId) throws Exception {
        String path = this.instance(instanceId);
        return this.status(path, this.read(path + "/info", EntInstanceCatalogData.class));
    }

    private static String identity(EntInstanceCatalogData value) {
        return value.getInstanceId() + " " + value.getIpAddress() + " " + value.getProcessId();
    }

    @Override
    public RecordPage<InstanceStatus> instances(String applicationName, DirectoryQuery query) throws Exception {
        if (applicationName == null) {
            String path = this.root + "/instances";
            return this.page(path, query, child -> {
                String item = path + "/" + child;
                return this.status(item, this.read(item + "/info", EntInstanceCatalogData.class));
            }, value -> identity(value.getInstance()), value -> value.getInstance().getUnitName());
        }
        String path = this.application(applicationName) + "/instances";
        return this.page(path, query, child -> {
            EntInstanceCatalogData archive = this.read(path + "/" + child, EntInstanceCatalogData.class);
            return this.status(this.root + "/instances/" + child, archive);
        }, value -> identity(value.getInstance()), value -> value.getInstance().getUnitName());
    }

    @Override
    public EntServiceCatalogData getService(String serviceId) throws Exception {
        return this.read(this.service(serviceId) + "/info", EntServiceCatalogData.class);
    }

    private void declaration(List<CuratorOp> ops, EntServiceCatalogData service) throws Exception {
        String path = this.service(service.getServiceId()) + "/info";
        Stat stat = new Stat();
        Map<String, Object> declaration = RegistryJson.copy(service, Map.class);
        try {
            byte[] previous = this.client.getData().storingStatIn(stat).forPath(path);
            Map<String, Object> merged = RegistryJson.read(new String(previous, StandardCharsets.UTF_8), Map.class);
            // 缺省的声明字段不抹掉已登记信息，也不覆盖详情里的治理控制。
            for (Map.Entry<String, Object> field : declaration.entrySet()) {
                if (field.getValue() != null) {
                    merged.put(field.getKey(), field.getValue());
                }
            }
            byte[] body = RegistryJson.write(merged).getBytes(StandardCharsets.UTF_8);
            ops.add(this.client.transactionOp().setData().withVersion(stat.getVersion()).forPath(path, body));
        } catch (KeeperException.NoNodeException absent) {
            this.parent(this.service(service.getServiceId()));
            byte[] body = RegistryJson.write(declaration).getBytes(StandardCharsets.UTF_8);
            ops.add(this.client.transactionOp().create().forPath(path, body));
        }
    }

    @Override
    public void saveService(EntServiceCatalogData service) throws Exception {
        this.mutate(ops -> this.declaration(ops, service));
    }

    @Override
    public RecordPage<EntServiceCatalogData> services(DirectoryQuery query) throws Exception {
        String path = this.root + "/services";
        return this.page(path, query, child -> this.read(path + "/" + child + "/info", EntServiceCatalogData.class), EntServiceCatalogData::getServiceId, value -> null);
    }

    @Override
    public Map<String, ServiceSummary> serviceSummaries(Collection<String> serviceIds) throws Exception {
        Map<String, ServiceSummary> result = new HashMap<>();
        for (String serviceId : serviceIds) {
            String path = this.service(serviceId);
            ServiceSummary summary = new ServiceSummary();
            summary.setProviderCount(this.children(path + "/providers").size());
            summary.setConsumerCount(this.children(path + "/consumers").size());
            List<String> protocols = new ArrayList<>();
            for (String protocol : this.children(path + "/protocols")) {
                if (!this.children(path + "/protocols/" + protocol).isEmpty()) {
                    protocols.add(new String(Base64.getUrlDecoder().decode(protocol), StandardCharsets.UTF_8));
                }
            }
            summary.setProtocols(protocols.toArray(String[]::new));
            result.put(serviceId, summary);
        }
        return result;
    }

    @Override
    public EntServiceRuntimeData getInstanceService(String instanceId, String serviceId) throws Exception {
        return this.read(this.instance(instanceId) + "/services/" + key(serviceId), EntServiceRuntimeData.class);
    }

    @Override
    public RecordPage<EntServiceRuntimeData> instanceServices(String instanceId, DirectoryQuery query) throws Exception {
        String path = this.instance(instanceId) + "/services";
        return this.page(path, query, child -> this.read(path + "/" + child, EntServiceRuntimeData.class), EntServiceCatalogData::getServiceId, value -> null);
    }

    @Override
    public RefServiceRelationData getMember(String serviceId, MetServiceType role, String instanceId) throws Exception {
        return this.memberAt(this.service(serviceId) + "/" + role(role) + "/" + key(instanceId), serviceId, role);
    }

    @Override
    public RecordPage<RefServiceRelationData> members(String serviceId, MetServiceType role, DirectoryQuery query) throws Exception {
        String path = this.service(serviceId) + "/" + role(role);
        return this.page(path, query, child -> this.memberAt(path + "/" + child, serviceId, role), value -> identity(value.getInstance()), value -> value.getInstance().getUnitName());
    }

    private RefServiceRelationData memberAt(String path, String serviceId, MetServiceType role) throws Exception {
        Map<?, ?> reference = this.read(path, Map.class);
        if (reference == null) {
            return null;
        }
        String instanceId = (String) reference.get("instanceId");
        String runtimePath = this.instance(instanceId) + "/runtime";
        EntInstanceRuntimeData instance = this.read(runtimePath, EntInstanceRuntimeData.class);
        EntServiceRuntimeData service = this.getInstanceService(instanceId, serviceId);
        if (instance == null || service == null || service.getRole() != role) {
            return null;
        }
        RefServiceRelationData result = new RefServiceRelationData();
        result.setInstance(instance);
        result.setService(service);
        return result;
    }

    private EntInstanceRuntimeData own(List<CuratorOp> ops, String instanceId) throws Exception {
        Integer version = this.ownerVersions.get(instanceId);
        String path = this.instance(instanceId);
        Stat runtime = this.client.checkExists().forPath(path + "/runtime");
        long session = this.client.getZookeeperClient().getZooKeeper().getSessionId();
        if (version == null || runtime == null || runtime.getEphemeralOwner() != session) {
            throw new IllegalStateException("Instance session is no longer owned: " + instanceId);
        }
        Stat owner = this.client.checkExists().forPath(path + "/owner");
        if (owner == null || owner.getVersion() != version) {
            throw new IllegalStateException("Instance was replaced by another session: " + instanceId);
        }
        ops.add(this.client.transactionOp().check().withVersion(version).forPath(path + "/owner"));
        return this.read(path + "/runtime", EntInstanceRuntimeData.class);
    }

    private static Set<String> protocols(EntServiceRuntimeData service) {
        return DirectoryFilter.protocolNames(service);
    }

    private void withdraw(List<CuratorOp> ops, String instanceId, EntServiceRuntimeData service) throws Exception {
        String base = this.service(service.getServiceId());
        this.removeGovernance(ops, instanceId, service.getServiceId());
        this.remove(ops, this.instance(instanceId) + "/services/" + key(service.getServiceId()));
        this.remove(ops, base + "/" + role(service.getRole()) + "/" + key(instanceId));
        for (String protocol : protocols(service)) {
            this.remove(ops, base + "/protocols/" + key(protocol) + "/" + key(instanceId));
        }
    }

    @Override
    public synchronized void registerInstance(EntInstanceRuntimeData value) throws Exception {
        this.requireOpen();
        String id = value.getInstanceId();
        String path = this.instance(id);
        this.parent(path);
        // owner 永不删除，它的版本用于隔离同名临时节点被删除重建后的旧写入。
        this.parent(path + "/owner");
        int[] committedVersion = new int[1];
        this.mutate(ops -> {
            Stat owner = this.client.checkExists().forPath(path + "/owner");
            Stat runtime = this.client.checkExists().forPath(path + "/runtime");
            long session = this.client.getZookeeperClient().getZooKeeper().getSessionId();
            if (this.ownerVersions.containsKey(id)) {
                if (owner.getVersion() != this.ownerVersions.get(id)) {
                    throw new IllegalStateException("Instance was replaced by another session: " + id);
                }
                if (runtime != null) {
                    EntInstanceRuntimeData previous = this.own(ops, id);
                    if (RegistryJson.sameData(previous, value)) {
                        committedVersion[0] = owner.getVersion();
                        return;
                    }
                }
            }
            boolean takeover = runtime != null && runtime.getEphemeralOwner() != session;
            if (takeover) {
                for (String child : this.children(path + "/services")) {
                    EntServiceRuntimeData service = this.read(path + "/services/" + child, EntServiceRuntimeData.class);
                    if (service != null) {
                        this.withdraw(ops, id, service);
                    }
                }
                this.removeGovernance(ops, id, null);
                this.createGovernance(ops, id, null, true);
                this.remove(ops, path + "/runtime");
                byte[] body = RegistryJson.write(value).getBytes(StandardCharsets.UTF_8);
                ops.add(this.client.transactionOp().create() //
                        .withMode(CreateMode.EPHEMERAL).forPath(path + "/runtime", body));
            } else {
                this.put(ops, path + "/runtime", value, true);
                this.createGovernance(ops, id, null, false);
                // 身份变化只刷新本实例已有成员，服务内容仍来自各自子项。
                for (String child : this.children(path + "/services")) {
                    EntServiceRuntimeData service = this.read(path + "/services/" + child, EntServiceRuntimeData.class);
                    if (service != null) {
                        this.member(ops, value, service);
                    }
                }
            }
            byte[] sessionBody = Long.toString(session).getBytes(StandardCharsets.UTF_8);
            ops.add(this.client.transactionOp().setData() //
                    .withVersion(owner.getVersion()).forPath(path + "/owner", sessionBody));
            committedVersion[0] = owner.getVersion() + 1;
            EntInstanceCatalogData archive = RegistryJson.copy(value, EntInstanceCatalogData.class);
            EntInstanceCatalogData previous = this.read(path + "/info", EntInstanceCatalogData.class);
            if (previous != null && !StringUtils.equals(previous.getApplicationName(), value.getApplicationName())) {
                this.remove(ops, this.application(previous.getApplicationName()) + "/instances/" + key(id));
            }
            this.put(ops, path + "/info", archive, false);
            EntApplicationCatalogData application = new EntApplicationCatalogData();
            application.setApplicationName(value.getApplicationName());
            this.put(ops, this.application(value.getApplicationName()) + "/info", application, false);
            this.put(ops, this.application(value.getApplicationName()) + "/instances/" + key(id), archive, false);
        });
        this.ownerVersions.put(id, committedVersion[0]);
    }

    private void member(List<CuratorOp> ops, EntInstanceRuntimeData instance, EntServiceRuntimeData service) throws Exception {
        String membersPath = this.service(service.getServiceId()) + "/" + role(service.getRole());
        String path = membersPath + "/" + key(instance.getInstanceId());
        // 反向目录只保存引用；版本变化仍通知服务订阅者重新读取最新的运行信息。
        this.put(ops, path, Map.of("instanceId", instance.getInstanceId()), true);
    }

    private List<String> governanceItems() {
        List<String> items = new ArrayList<>(List.of("state", "metadata"));
        for (RuleKind kind : RuleKind.values()) {
            items.add(kind.name());
        }
        return items;
    }

    private void createGovernance(List<CuratorOp> ops, String instanceId, String serviceId, boolean replaced) throws Exception {
        String scope = this.configuration(instanceId, serviceId);
        this.parent(scope);
        // 临时节点不能拥有子节点：持久目录下的每个正文槽位均由应用会话创建。
        for (String item : this.governanceItems()) {
            String path = scope + "/" + item;
            if (replaced || this.client.checkExists().forPath(path) == null) {
                ops.add(this.client.transactionOp().create().withMode(CreateMode.EPHEMERAL).forPath(path, new byte[0]));
            }
        }
    }

    private void removeGovernance(List<CuratorOp> ops, String instanceId, String serviceId) throws Exception {
        String scope = this.configuration(instanceId, serviceId);
        for (String item : this.governanceItems()) {
            this.remove(ops, scope + "/" + item);
        }
    }

    private int advanceOwner(List<CuratorOp> ops, String instanceId) throws Exception {
        String path = this.instance(instanceId) + "/owner";
        Stat stat = new Stat();
        byte[] body = this.client.getData().storingStatIn(stat).forPath(path);
        ops.add(this.client.transactionOp().setData().withVersion(stat.getVersion()).forPath(path, body));
        return stat.getVersion() + 1;
    }

    @Override
    public synchronized void registerService(String instanceId, EntServiceRuntimeData service) throws Exception {
        int[] committedVersion = { this.ownerVersions.getOrDefault(instanceId, -1) };
        this.mutate(ops -> {
            EntInstanceRuntimeData instance = this.own(ops, instanceId);
            EntServiceRuntimeData previous = this.getInstanceService(instanceId, service.getServiceId());
            if (RegistryJson.sameData(previous, service)) {
                return;
            }
            this.declaration(ops, service);
            this.createGovernance(ops, instanceId, service.getServiceId(), false);
            String base = this.service(service.getServiceId());
            if (previous != null) {
                if (previous.getRole() != service.getRole()) {
                    this.remove(ops, base + "/" + role(previous.getRole()) + "/" + key(instanceId));
                }
                Set<String> removed = protocols(previous);
                removed.removeAll(protocols(service));
                for (String protocol : removed) {
                    this.remove(ops, base + "/protocols/" + key(protocol) + "/" + key(instanceId));
                }
            }
            this.put(ops, this.instance(instanceId) + "/services/" + key(service.getServiceId()), service, true);
            this.member(ops, instance, service);
            for (String protocol : protocols(service)) {
                this.put(ops, base + "/protocols/" + key(protocol) + "/" + key(instanceId), protocol, true);
            }
            committedVersion[0] = this.advanceOwner(ops, instanceId);
        });
        this.ownerVersions.put(instanceId, committedVersion[0]);
    }

    @Override
    public synchronized void unregisterService(String instanceId, String serviceId) throws Exception {
        int[] committedVersion = { this.ownerVersions.getOrDefault(instanceId, -1) };
        this.mutate(ops -> {
            this.own(ops, instanceId);
            EntServiceRuntimeData previous = this.getInstanceService(instanceId, serviceId);
            if (previous != null) {
                this.withdraw(ops, instanceId, previous);
                committedVersion[0] = this.advanceOwner(ops, instanceId);
            }
        });
        this.ownerVersions.put(instanceId, committedVersion[0]);
    }

    private String configuration(String instanceId, String serviceId) {
        if (instanceId != null) {
            String path = this.instance(instanceId) + "/governance";
            return serviceId == null ? path + "/instance" : path + "/services/" + key(serviceId);
        }
        return serviceId == null ? this.root + "/global/governance" : this.service(serviceId) + "/governance";
    }

    private <T> EntityRecord<T> versioned(String path, Class<T> type) throws Exception {
        this.requireOpen();
        Stat stat = new Stat();
        try {
            byte[] body = this.client.getData().storingStatIn(stat).forPath(path);
            EntityRecord<T> result = new EntityRecord<>();
            if (body.length == 0) {
                return null;
            }
            T data = RegistryJson.read(new String(body, StandardCharsets.UTF_8), type);
            if (data == null) {
                return null;
            }
            result.setData(data);
            result.setRevision(stat.getCzxid() + ":" + stat.getVersion());
            return result;
        } catch (KeeperException.NoNodeException absent) {
            return null;
        }
    }

    private boolean writeGovernance(String path, String ownerPath, EntityRecord<?> value, boolean delete, boolean preserveControls) throws Exception {
        this.requireOpen();
        Stat owner = ownerPath == null ? null : this.client.checkExists().forPath(ownerPath);
        Stat stat = new Stat();
        Map<String, Object> previous = null;
        boolean exists = true;
        try {
            byte[] body = this.client.getData().storingStatIn(stat).forPath(path);
            if (body.length != 0) {
                previous = RegistryJson.read(new String(body, StandardCharsets.UTF_8), Map.class);
            }
        } catch (KeeperException.NoNodeException absent) {
            exists = false;
        }
        if (ownerPath != null && (owner == null || !exists)) {
            if (delete) {
                return false;
            }
            throw new IllegalStateException("Governance target is not registered: " + path);
        }
        boolean configured = previous != null;
        if (configured && preserveControls) {
            configured = previous.containsKey("enabled") || previous.containsKey("block");
        }
        if (delete && !configured) {
            return false;
        }
        String revision = configured ? stat.getCzxid() + ":" + stat.getVersion() : null;
        if (!StringUtils.equals(revision, value.getRevision())) {
            throw new ConcurrentModificationException("Governance record changed: " + path);
        }

        // 全局 state 同时承载规则开关，状态更新和清除均保留控制字段。
        Map<String, Object> updated = delete ? null : RegistryJson.copy(value.getData(), Map.class);
        if (preserveControls) {
            if (updated == null) {
                updated = new LinkedHashMap<>();
            }
            if (previous != null) {
                for (String field : List.of("ruleEnabled", "ruleCleared")) {
                    if (previous.containsKey(field)) {
                        updated.put(field, previous.get(field));
                    }
                }
            }
        }
        byte[] body = RegistryJson.write(updated).getBytes(StandardCharsets.UTF_8);
        List<CuratorOp> operations = new ArrayList<>();
        if (owner != null) {
            operations.add(this.client.transactionOp().check().withVersion(owner.getVersion()).forPath(ownerPath));
        }
        if (exists) {
            operations.add(this.client.transactionOp().setData().withVersion(stat.getVersion()).forPath(path, body));
        } else {
            this.parent(path.substring(0, path.lastIndexOf('/')));
            operations.add(this.client.transactionOp().create().forPath(path, body));
        }
        try {
            this.client.transaction().forOperations(operations);
            return true;
        } catch (KeeperException.BadVersionException | KeeperException.NodeExistsException | KeeperException.NoNodeException conflict) {
            throw new ConcurrentModificationException("Governance record changed: " + path, conflict);
        }
    }

    private String governanceOwner(String instanceId) {
        return instanceId == null ? null : this.instance(instanceId) + "/owner";
    }

    @Override
    public EntityRecord<EntConfigurationData> getConfiguration(String instanceId, String serviceId) throws Exception {
        String path = this.configuration(instanceId, serviceId) + "/state";
        if (instanceId == null && serviceId == null) {
            EntityRecord<Map> record = this.versioned(path, Map.class);
            if (record == null || !(record.getData().containsKey("enabled") || record.getData().containsKey("block"))) {
                return null;
            }
            EntityRecord<EntConfigurationData> result = new EntityRecord<>();
            result.setRevision(record.getRevision());
            result.setData(RegistryJson.copy(record.getData(), EntConfigurationData.class));
            return result;
        }
        return this.versioned(path, EntConfigurationData.class);
    }

    @Override
    public void saveConfiguration(String instanceId, String serviceId, EntityRecord<EntConfigurationData> value) throws Exception {
        String path = this.configuration(instanceId, serviceId) + "/state";
        boolean global = instanceId == null && serviceId == null;
        this.writeGovernance(path, this.governanceOwner(instanceId), value, false, global);
    }

    @Override
    public boolean deleteConfiguration(String instanceId, String serviceId, String revision) throws Exception {
        String path = this.configuration(instanceId, serviceId) + "/state";
        boolean global = instanceId == null && serviceId == null;
        EntityRecord<EntConfigurationData> value = new EntityRecord<>();
        value.setRevision(revision);
        return this.writeGovernance(path, this.governanceOwner(instanceId), value, true, global);
    }

    private String ruleDetail(String instanceId, String serviceId) {
        if (instanceId != null) {
            String path = this.instance(instanceId);
            return serviceId == null ? path + "/runtime" : path + "/services/" + key(serviceId);
        }
        return serviceId == null ? this.configuration(null, null) + "/state" : this.service(serviceId) + "/info";
    }

    @Override
    public EntityRecord<EntRuleConfigurationData> getRule(String instanceId, String serviceId, RuleKind kind) throws Exception {
        this.requireOpen();
        String path = this.configuration(instanceId, serviceId) + "/" + kind.name();
        return this.rules.get(this.ruleDetail(instanceId, serviceId), path, kind);
    }

    @Override
    public void saveRule(String instanceId, String serviceId, RuleKind kind, EntityRecord<EntRuleConfigurationData> value) throws Exception {
        this.requireOpen();
        String path = this.configuration(instanceId, serviceId) + "/" + kind.name();
        String owner = this.governanceOwner(instanceId);
        this.rules.save(this.ruleDetail(instanceId, serviceId), path, owner, serviceId, kind, value, false);
    }

    @Override
    public boolean deleteRule(String instanceId, String serviceId, RuleKind kind, String revision) throws Exception {
        this.requireOpen();
        String path = this.configuration(instanceId, serviceId) + "/" + kind.name();
        String owner = this.governanceOwner(instanceId);
        EntityRecord<EntRuleConfigurationData> value = new EntityRecord<>();
        value.setRevision(revision);
        return this.rules.save(this.ruleDetail(instanceId, serviceId), path, owner, serviceId, kind, value, true);
    }

    @Override
    public EntityRecord<EntMetadataData> getMetadata(String instanceId, String serviceId) throws Exception {
        return this.versioned(this.configuration(instanceId, serviceId) + "/" + "metadata", EntMetadataData.class);
    }

    @Override
    public void saveMetadata(String instanceId, String serviceId, EntityRecord<EntMetadataData> value) throws Exception {
        String path = this.configuration(instanceId, serviceId) + "/metadata";
        this.writeGovernance(path, this.governanceOwner(instanceId), value, false, false);
    }

    @Override
    public boolean deleteMetadata(String instanceId, String serviceId, String revision) throws Exception {
        String path = this.configuration(instanceId, serviceId) + "/metadata";
        EntityRecord<EntMetadataData> value = new EntityRecord<>();
        value.setRevision(revision);
        return this.writeGovernance(path, this.governanceOwner(instanceId), value, true, false);
    }

    private Map<String, String> versions(List<String> paths, String detailPath) throws Exception {
        Map<String, String> values = new TreeMap<>();
        for (String path : paths) {
            if (StringUtils.equals(path, detailPath)) {
                Map<?, ?> detail = this.read(path, Map.class);
                if (detail != null) {
                    Map<String, Object> controls = new TreeMap<>();
                    controls.put("ruleEnabled", detail.get("ruleEnabled"));
                    controls.put("ruleCleared", detail.get("ruleCleared"));
                    values.put(path, RegistryJson.write(controls));
                }
                continue;
            }
            // 空目录的创建不构成业务变化，只比较正文叶子或成员引用。
            for (String child : this.children(path)) {
                Stat stat = this.client.checkExists().forPath(path + "/" + child);
                if (stat != null) {
                    values.put(path + "/" + child, stat.getCzxid() + ":" + stat.getVersion());
                }
            }
        }
        return values;
    }

    private synchronized Cancellable watch(List<String> paths, String detailPath, Runnable listener) {
        this.requireOpen();
        Callable<Map<String, String>> snapshot = () -> this.versions(paths, detailPath);
        Consumer<ZookeeperStorageWatch> onCancel = this.watches::remove;
        ZookeeperStorageWatch watch = new ZookeeperStorageWatch(this.client, snapshot, listener, this.executor, onCancel);
        CuratorCacheListener events = CuratorCacheListener.builder() //
                .forAll((type, previous, current) -> watch.signal()) //
                .forInitialized(watch::signal).build();
        // 一个治理订阅同时覆盖正文目录和开关所在的详情，不订阅整个注册中心树。
        for (String path : paths) {
            CuratorCache cache = CuratorCache.build(this.client, path);
            cache.listenable().addListener(events);
            watch.addOwnedCache(cache);
        }
        this.watches.add(watch);
        try {
            watch.start();
        } catch (RuntimeException failure) {
            watch.cancel();
            throw failure;
        }
        return watch;
    }

    @Override
    public Cancellable watchProviders(String serviceId, Runnable listener) {
        return this.watch(List.of(this.service(serviceId) + "/providers"), null, listener);
    }

    @Override
    public Cancellable watchConfiguration(String instanceId, String serviceId, Runnable listener) {
        String scope = this.configuration(instanceId, serviceId);
        String detail = this.ruleDetail(instanceId, serviceId);
        List<String> paths = instanceId == null && serviceId == null ? List.of(scope) : List.of(scope, detail);
        return this.watch(paths, instanceId == null && serviceId == null ? null : detail, listener);
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.client.getConnectionStateListenable().removeListener(this.connectionListener);
        for (ZookeeperStorageWatch watch : new ArrayList<>(this.watches)) {
            watch.cancel();
        }
        this.executor.shutdownNow();
        this.client.close();
    }
}
