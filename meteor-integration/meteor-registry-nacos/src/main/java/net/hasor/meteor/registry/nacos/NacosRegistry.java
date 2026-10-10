/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.registry.shared.storage.RecordStorageAccess;
import net.hasor.meteor.registry.shared.storage.StorageDocument;
import net.hasor.meteor.registry.shared.storage.StorageKeyUtils;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/**
 * 临时正文由 Naming 持有，起点索引只保存引用；持久正文使用有界目录和分片 CAS。
 */
final class NacosRegistry extends RecordStorageAccess {
    private static final String                       PREFIX        = "meteor.v2.";
    private final        NamingService                naming;
    private final        ConfigService                configs;
    private final        String                       group;
    private final        long                         timeout;
    private final        Map<String, StorageDocument> owned         = new HashMap<>();
    private final        Set<NacosStorageWatch>       subscriptions = ConcurrentHashMap.newKeySet();
    private              ScheduledExecutorService     watchExecutor;

    @Override
    public synchronized Cancellable watchEntities(EntityQuery query, Runnable listener) {
        this.requireOpen();
        if (query == null || query.getType() == null) {
            throw new IllegalArgumentException("Entity type is required");
        }

        EntityQuery selection = query.copy();
        return this.watch(List.of("e." + selection.getType().name()), () -> {
            return this.readVersions(selection);
        }, listener);
    }

    @Override
    public synchronized Cancellable watchRelations(RelationQuery query, Runnable listener) {
        this.requireOpen();
        boolean scoped = query != null && (query.getType() != null || query.getSource() != null || query.getTarget() != null);
        if (!scoped) {
            throw new IllegalArgumentException("Specify relation type or endpoint");
        }

        RelationQuery selection = query.copy();
        List<String> families = new ArrayList<>();
        for (RelationType type : RelationType.values()) {
            if (selection.getType() == null || selection.getType() == type) {
                families.add("r." + type.name());
            }
        }
        return this.watch(families, () -> this.readVersions(selection), listener);
    }

    private Map<String, String> readVersions(EntityQuery query) throws Exception {
        EntityQuery pageQuery = query.copy();
        pageQuery.setLimit(1000);
        pageQuery.setCursor(null);
        Map<String, String> versions = new TreeMap<>();

        do {
            RecordPage<EntityRecord> page = this.findEntities(pageQuery);
            for (EntityRecord record : page.records()) {
                EntityRef entity = record.getRef();
                String key = StorageKeyUtils.entity(entity);
                versions.put(key, record.getRevision());
            }
            pageQuery.setCursor(page.nextCursor());
        } while (pageQuery.getCursor() != null);
        return versions;
    }

    private Map<String, String> readVersions(RelationQuery query) throws Exception {
        RelationQuery pageQuery = query.copy();
        pageQuery.setLimit(1000);
        pageQuery.setCursor(null);
        Map<String, String> versions = new TreeMap<>();

        do {
            RecordPage<RelationRecord> page = this.findRelations(pageQuery);
            for (RelationRecord record : page.records()) {
                String key = StorageKeyUtils.relation(record.getType(), record.getSource(), record.getTarget());
                versions.put(key, record.getRevision());
            }
            pageQuery.setCursor(page.nextCursor());
        } while (pageQuery.getCursor() != null);
        return versions;
    }

    private Cancellable watch(List<String> families, Callable<Map<String, String>> snapshot, Runnable listener) {
        if (this.watchExecutor == null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "Meteor-nacos-watch-" + this.tempSessionId);
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            this.watchExecutor = executor;
        }
        NacosStorageWatch watch = new NacosStorageWatch(this.naming, this.configs, this.group, this.timeout, families, snapshot, listener, this.watchExecutor, this.subscriptions::remove);
        this.subscriptions.add(watch);
        watch.start();
        return watch;
    }

    NacosRegistry(NamingService naming, ConfigService configs, String group, long timeout) {
        this.naming = naming;
        this.configs = configs;
        this.group = group;
        this.timeout = timeout;
    }

    private static String family(String key) {
        return key.substring(0, key.lastIndexOf('.'));
    }

    private static String shard(String key) {
        return StorageKeyUtils.hash(key).substring(0, 2);
    }

    private static String bucket(String key) {
        return PREFIX + "config." + family(key) + "." + shard(key);
    }

    private static String primary(StorageDocument record) {
        String key;
        if (record.getEntity() != null) {
            key = record.getKey();
        } else {
            key = StorageKeyUtils.entity(record.getRelation().getTarget());
        }
        return PREFIX + record.getKind() + "." + record.getType() + "." + StorageKeyUtils.hash(key);
    }

    private static String sourceIndex(RelationRecord relation) {
        String source = StorageKeyUtils.entity(relation.getSource());
        return PREFIX + "source." + relation.getType() + "." + StorageKeyUtils.hash(source);
    }

    private static String cluster(StorageDocument record) {
        return "M" + StorageKeyUtils.hash(record.getKey(), record.getSessionId());
    }

    private static String md5(String value) throws Exception {
        String content = value == null ? "" : value;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
        return HexFormat.of().formatHex(digest);
    }

    private RecordBucket readBucket(String content) throws Exception {
        return content == null ? new RecordBucket() : RegistryJson.read(content, RecordBucket.class);
    }

    private List<Instance> nodes(String name) throws Exception {
        return this.naming.getAllInstances(name, this.group, false);
    }

    private StorageDocument temporary(String key, String name, boolean liveOnly) throws Exception {
        StorageDocument found = null;
        for (Instance node : this.nodes(name)) {
            if (liveOnly && (!node.isHealthy() || !node.isEnabled())) {
                continue;
            }

            String body = node.getMetadata().get("record");
            if (body == null) {
                continue;
            }

            StorageDocument value = RegistryJson.read(body, StorageDocument.class);
            if (StringUtils.equals(key, value.getKey())) {
                if (found != null && !StringUtils.equals(found.getSessionId(), value.getSessionId())) {
                    throw new IllegalStateException("Conflicting owners for temporary record: " + key);
                }
                found = value;
            }
        }
        return found;
    }

    private StorageDocument persistent(String key) throws Exception {
        return this.readBucket(this.configs.getConfig(bucket(key), this.group, this.timeout)).getRecords().get(key);
    }

    @Override
    protected StorageDocument readDocument(String key) throws Exception {
        StorageDocument saved = this.persistent(key);
        if (saved != null) {
            return saved;
        } else {
            return this.temporary(key, PREFIX + family(key) + "." + StorageKeyUtils.hash(key), true);
        }
    }

    @Override
    protected StorageDocument readRelationDocument(RelationType type, EntityRef source, EntityRef target) throws Exception {
        String key = StorageKeyUtils.relation(type, source, target);
        StorageDocument durable = this.persistent(key);
        if (durable != null) {
            return durable;
        }
        String name = PREFIX + "r." + type.name() + "." + StorageKeyUtils.hash(StorageKeyUtils.entity(target));
        return this.temporary(key, name, true);
    }

    private List<String> names(String prefix) throws Exception {
        List<String> result = new ArrayList<>();
        int page = 1;
        ListView<String> view;
        do {
            view = this.naming.getServicesOfServer(page, 100, this.group);
            for (String name : view.getData()) {
                if (name.startsWith(prefix)) {
                    result.add(name);
                }
            }
        } while (page++ * 100 < view.getCount());
        return result;
    }

    private void collect(String name, Map<String, StorageDocument> result, boolean reference) throws Exception {
        for (Instance node : this.nodes(name)) {
            if (!node.isHealthy() || !node.isEnabled()) {
                continue;
            }

            StorageDocument record;
            if (reference) {
                String key = node.getMetadata().get("key");
                String target = node.getMetadata().get("primary");
                if (key == null || target == null) {
                    continue;
                }

                record = this.temporary(key, target, true);
            } else {
                String json = node.getMetadata().get("record");
                if (json == null) {
                    continue;
                }
                record = RegistryJson.read(json, StorageDocument.class);
            }

            if (record != null) {
                StorageDocument previous = result.put(record.getKey(), record);
                if (previous != null && !StringUtils.equals(previous.getSessionId(), record.getSessionId())) {
                    throw new IllegalStateException("Conflicting temporary record owners");
                }
            }
        }
    }

    @Override
    protected List<StorageDocument> queryDocuments(String kind, String type, EntityRef source, EntityRef target) throws Exception {
        if (StringUtils.equals("e", kind)) {
            source = null;
        }
        Map<String, StorageDocument> result = new LinkedHashMap<>();
        List<String> types = new ArrayList<>();
        if (type == null) {
            for (RelationType relationType : RelationType.values()) {
                types.add(relationType.name());
            }
        } else {
            types.add(type);
        }
        for (String current : types) {
            String base = PREFIX + kind + "." + current + ".";
            if (target != null) {
                this.collect(base + StorageKeyUtils.hash(StorageKeyUtils.entity(target)), result, false);
            } else if (source != null) {
                String sourceKey = StorageKeyUtils.entity(source);
                String index = PREFIX + "source." + current + "." + StorageKeyUtils.hash(sourceKey);
                this.collect(index, result, true);
            } else {
                for (String name : this.names(base)) {
                    this.collect(name, result, false);
                }
            }

            String catalogName = PREFIX + "catalog." + kind + "." + current;
            String catalog = this.configs.getConfig(catalogName, this.group, this.timeout);
            if (catalog != null) {
                for (String shard : RegistryJson.read(catalog, String[].class)) {
                    String shardName = PREFIX + "config." + kind + "." + current + "." + shard;
                    String body = this.configs.getConfig(shardName, this.group, this.timeout);
                    for (StorageDocument value : this.readBucket(body).getRecords().values()) {
                        if (result.put(value.getKey(), value) != null) {
                            throw new IllegalStateException("Record has conflicting lifecycles");
                        }
                    }
                }
            }
        }

        return new ArrayList<>(result.values());
    }

    private void includeShard(String key) throws Exception {
        String name = PREFIX + "catalog." + family(key);
        for (int attempt = 0; attempt < 8; attempt++) {
            String old = this.configs.getConfig(name, this.group, this.timeout);
            Set<String> shards = new TreeSet<>();
            if (old != null) {
                String[] existing = RegistryJson.read(old, String[].class);
                shards.addAll(Arrays.asList(existing));
            }
            if (!shards.add(shard(key))) {
                return;
            }
            if (this.configs.publishConfigCas(name, this.group, RegistryJson.write(shards), md5(old))) {
                return;
            }
        }
        throw new ConcurrentModificationException("Nacos record catalog remains busy");
    }

    private StorageDocument updatePersistent(StorageDocument input, boolean delete) throws Exception {
        String dataId = bucket(input.getKey());
        for (int attempt = 0; attempt < 8; attempt++) {
            String old = this.configs.getConfig(dataId, this.group, this.timeout);
            RecordBucket records = this.readBucket(old);
            StorageDocument previous = records.getRecords().get(input.getKey());
            if (delete && previous == null) {
                return null;
            }

            StorageDocument saved;
            if (delete) {
                this.checkMutation(previous, input);
                saved = records.getRecords().remove(input.getKey());
            } else {
                saved = this.prepareWrite(previous, input);
                records.getRecords().put(input.getKey(), saved);
            }

            String json = RegistryJson.write(records);
            if (json.getBytes(StandardCharsets.UTF_8).length > 65536) {
                throw new IllegalStateException("Nacos record shard exceeds 64 KiB; reduce record content or split the registry scope");
            }
            // 通过版本及容量校验后才发布新分片的目录项。
            if (!delete && attempt == 0) {
                this.includeShard(input.getKey());
            }
            if (this.configs.publishConfigCas(dataId, this.group, json, md5(old))) {
                return saved;
            }
        }
        throw new ConcurrentModificationException("Nacos record shard remains busy");
    }

    private void register(String service, StorageDocument record, Map<String, String> metadata) throws Exception {
        Instance node = new Instance();
        node.setIp("127.0.0.1");
        node.setPort(1);
        node.setClusterName(cluster(record));
        node.setEphemeral(true);
        node.setMetadata(metadata);
        this.naming.registerInstance(service, this.group, node);
    }

    @Override
    protected StorageDocument writeDocument(StorageDocument input) throws Exception {
        StorageDocument temporary = this.temporary(input.getKey(), primary(input), false);
        if (input.getLifecycle() == RecordLifecycle.PERSISTENT) {
            // 临时记录不得转换生命周期；持久记录的版本检查由分片 CAS 完成。
            if (temporary != null) {
                this.checkMutation(temporary, input);
            }
            return this.updatePersistent(input, false);
        }

        StorageDocument durable = this.persistent(input.getKey());
        if (durable != null && temporary != null) {
            throw new IllegalStateException("Record has conflicting lifecycles");
        }

        StorageDocument saved = this.prepareWrite(durable != null ? durable : temporary, input);

        // 先登记引用，再提交正文；失败后的索引不会产生虚假查询结果。周期重新注册修复缺失索引。
        this.owned.put(saved.getKey(), saved);
        if (saved.getRelation() != null) {
            String index = sourceIndex(saved.getRelation());
            Map<String, String> metadata = Map.of("key", saved.getKey(), "primary", primary(saved));
            this.register(index, saved, metadata);
        }

        this.register(primary(saved), saved, Map.of("record", RegistryJson.write(saved)));
        return saved;
    }

    private void unregister(StorageDocument record) throws Exception {
        this.naming.deregisterInstance(primary(record), this.group, "127.0.0.1", 1, cluster(record));
        if (record.getRelation() != null) {
            this.naming.deregisterInstance(sourceIndex(record.getRelation()), this.group, "127.0.0.1", 1, cluster(record));
        }
    }

    @Override
    protected boolean removeDocument(StorageDocument input) throws Exception {
        StorageDocument durable = this.persistent(input.getKey());
        StorageDocument temporary = this.temporary(input.getKey(), primary(input), false);
        StorageDocument previous = durable != null ? durable : temporary;
        if (previous == null) {
            return false;
        }

        this.checkMutation(previous, input);
        if (previous.getLifecycle() == RecordLifecycle.PERSISTENT) {
            return this.updatePersistent(input, true) != null;
        }

        this.unregister(previous);
        this.owned.remove(previous.getKey());
        return true;
    }

    @Override
    protected void closeStorage() throws Exception {
        this.subscriptions.forEach(NacosStorageWatch::cancel);
        if (this.watchExecutor != null) {
            this.watchExecutor.shutdownNow();
        }
        Exception error = null;
        for (StorageDocument record : this.owned.values()) {
            try {
                this.unregister(record);
            } catch (Exception failure) {
                if (error == null) {
                    error = failure;
                } else {
                    error.addSuppressed(failure);
                }
            }
        }

        this.owned.clear();
        try {
            this.naming.shutDown();
        } catch (Exception failure) {
            if (error == null) {
                error = failure;
            } else {
                error.addSuppressed(failure);
            }
        }

        try {
            this.configs.shutDown();
        } catch (Exception failure) {
            if (error == null) {
                error = failure;
            } else {
                error.addSuppressed(failure);
            }
        }

        if (error != null) {
            throw error;
        }
    }
}
