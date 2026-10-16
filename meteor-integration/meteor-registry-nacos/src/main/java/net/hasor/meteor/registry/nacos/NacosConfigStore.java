/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.nio.charset.StandardCharsets;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.codec.MD5;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 持久正文按业务键点查，并管理 Config 客户端的订阅与生命周期。 */
final class NacosConfigStore implements AutoCloseable {
    private static final int           INDEX_BUCKETS = 16;
    private final        ConfigService configs;
    private final        String        group;
    private final        long          timeout;

    NacosConfigStore(ConfigService configs, String group, long timeout) {
        this.configs = configs;
        this.group = group;
        this.timeout = timeout;
    }

    String read(String key) throws Exception {
        if (key.length() > 256) {
            throw new IllegalArgumentException("Nacos dataId exceeds 256 characters: " + key);
        }
        return this.configs.getConfig(key, this.group, this.timeout);
    }

    <T> T get(String key, Class<T> type) throws Exception {
        String content = this.read(key);
        return content == null ? null : RegistryJson.read(content, type);
    }

    boolean compareAndSet(String key, String previous, String content) throws Exception {
        String original = previous == null ? "" : previous;
        String md5 = MD5.getMD5(original.getBytes(StandardCharsets.UTF_8));
        return this.configs.publishConfigCas(key, this.group, content, md5);
    }

    boolean compareAndSetRule(String key, String previous, String content) throws Exception {
        String original = previous == null ? "" : previous;
        String md5 = MD5.getMD5(original.getBytes(StandardCharsets.UTF_8));
        return this.configs.publishConfigCas(key, this.group, content, md5, "xml");
    }

    void removeRule(String key) throws Exception {
        // Nacos 没有带 CAS 的删除；调用方在治理范围锁内核对内容版本后执行。
        if (!this.configs.removeConfig(key, this.group)) {
            throw new IllegalStateException("Cannot remove Nacos rule: " + key);
        }
    }

    <T> void update(String key, Class<T> type, UnaryOperator<T> mutation) throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            backoff(attempt);
            String previous = this.read(key);
            T old = previous == null ? null : RegistryJson.read(previous, type);
            String content = RegistryJson.write(mutation.apply(old));
            if (StringUtils.equals(previous, content) || this.compareAndSet(key, previous, content)) {
                return;
            }
        }
        throw new ConcurrentModificationException("Nacos record remains busy: " + key);
    }

    void index(String kind, String id, String key) throws Exception {
        int bucket = Math.floorMod(id.hashCode(), INDEX_BUCKETS);
        String indexKey = "index." + kind + "." + bucket;
        for (int attempt = 0; attempt < 8; attempt++) {
            backoff(attempt);
            String previous = this.read(indexKey);
            Map<String, String> entries = entries(previous);
            if (StringUtils.equals(key, entries.put(id, key))) {
                return;
            }
            StringBuilder content = new StringBuilder();
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                Map<String, String> reference = Map.of("id", entry.getKey(), "key", entry.getValue());
                content.append(RegistryJson.write(reference)).append('\n');
            }
            if (this.compareAndSet(indexKey, previous, content.toString())) {
                return;
            }
        }
        throw new ConcurrentModificationException("Nacos index remains busy: " + indexKey);
    }

    Map<String, String> references(String kind) throws Exception {
        Map<String, String> references = new TreeMap<>();
        for (int bucket = 0; bucket < INDEX_BUCKETS; bucket++) {
            references.putAll(entries(this.read("index." + kind + "." + bucket)));
        }
        return references;
    }

    void subscribe(String dataId, Listener listener) throws Exception {
        this.configs.addListener(dataId, this.group, listener);
    }

    void unsubscribe(String dataId, Listener listener) throws Exception {
        this.configs.removeListener(dataId, this.group, listener);
    }

    boolean isAvailable() {
        return StringUtils.equals("UP", this.configs.getServerStatus());
    }

    private static Map<String, String> entries(String content) throws Exception {
        Map<String, String> result = new TreeMap<>();
        if (content != null) {
            for (String line : content.split("\n")) {
                if (!line.isBlank()) {
                    Map<?, ?> entry = RegistryJson.read(line, Map.class);
                    result.put((String) entry.get("id"), (String) entry.get("key"));
                }
            }
        }
        return result;
    }

    static void backoff(int attempt) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Nacos operation interrupted");
        }
        if (attempt > 0) {
            TimeUnit.MILLISECONDS.sleep(Math.min(25L << (attempt - 1), 200L));
        }
    }

    @Override
    public void close() throws Exception {
        this.configs.shutDown();
    }
}
