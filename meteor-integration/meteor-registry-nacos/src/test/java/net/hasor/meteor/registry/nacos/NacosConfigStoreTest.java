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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.alibaba.nacos.api.config.ConfigService;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.codec.MD5;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 模拟服务端持久 CAS 已推进、Config 查询快照稍后才追上的自动合并边界。 */
public class NacosConfigStoreTest {
    private static final String GROUP = "meteor-config-store-unit";
    private ConfigService configs;

    @BeforeAll
    public static void initializeSerialization() throws Exception {
        // JSON 类加载和覆盖率插桩先完成，短 deadline 场景只衡量自动合并的时间预算。
        String document = RegistryJson.write(Map.of("name", "initialized"));
        RegistryJson.read(document, Map.class);
    }

    @BeforeEach
    public void prepareConfigService() {
        // 代理类准备不属于 CAS 超时预算，每个场景使用独立 mock。
        this.configs = mock(ConfigService.class);
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void indexMergesExistingApplicationsAfterMoreThanEightStaleReads() throws Exception {
        // writer 和 reader 是真实场景中落到同一索引桶的两个合法应用名。
        ConfigService configs = this.configs;
        String index = "index.applications.6";
        String stale = "{\"id\":\"other\",\"key\":\"applications.other\"}\n";
        Map<String, String> writer = Map.of("id", "composition-writer", "key", "applications.composition-writer");
        String committed = stale + RegistryJson.write(writer) + '\n';
        AtomicInteger reads = new AtomicInteger();
        when(configs.getConfig(index, GROUP, 5000)) //
                .thenAnswer(call -> reads.incrementAndGet() <= 8 ? stale : committed);
        String expectedMd5 = MD5.getMD5(committed.getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> persisted = new AtomicReference<>();
        when(configs.publishConfigCas(eq(index), eq(GROUP), anyString(), anyString())).thenAnswer(call -> {
            // CAS 针对持久版本，而前八次读取仍是旧快照。
            if (!StringUtils.equals(expectedMd5, call.getArgument(3))) {
                return false;
            }
            persisted.set(call.getArgument(2));
            return true;
        });

        // 第九次读取终于见到当前版本，必须继续合并，不能因固定次数提前报忙。
        NacosConfigStore store = new NacosConfigStore(configs, GROUP, 5000);
        store.index("applications", "composition-reader", "applications.composition-reader");

        // 新条目加入，同时保留同桶中其他应用的原有目录。
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : persisted.get().split("\n")) {
            Map<?, ?> entry = RegistryJson.read(line, Map.class);
            entries.put((String) entry.get("id"), (String) entry.get("key"));
        }
        assertEquals(9, reads.get());
        assertEquals("applications.other", entries.get("other"));
        assertEquals("applications.composition-writer", entries.get("composition-writer"));
        assertEquals("applications.composition-reader", entries.get("composition-reader"));
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void updatePreservesAdministrativeFieldsAfterMoreThanEightStaleReads() throws Exception {
        // 服务详情的持久版本已经带有管理字段，但查询缓存尚未包含它们。
        ConfigService configs = this.configs;
        String key = "services.echo";
        String stale = "{\"name\":\"before\"}";
        String committed = "{\"name\":\"before\",\"ruleEnabled\":{\"SERVICE_ROUTE\":false}}";
        AtomicInteger reads = new AtomicInteger();
        when(configs.getConfig(key, GROUP, 5000)) //
                .thenAnswer(call -> reads.incrementAndGet() <= 8 ? stale : committed);
        String expectedMd5 = MD5.getMD5(committed.getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> persisted = new AtomicReference<>();
        when(configs.publishConfigCas(eq(key), eq(GROUP), anyString(), anyString())).thenAnswer(call -> {
            if (!StringUtils.equals(expectedMd5, call.getArgument(3))) {
                return false;
            }
            persisted.set(call.getArgument(2));
            return true;
        });

        // 每次冲突都从实际可见版本重新执行声明合并。
        NacosConfigStore store = new NacosConfigStore(configs, GROUP, 5000);
        store.update(key, Map.class, document -> {
            Map<String, Object> updated = new LinkedHashMap<>(document);
            updated.put("name", "after");
            return updated;
        });

        // 业务声明更新成功，原有治理开关仍保留且未被旧快照覆盖。
        Map<?, ?> actual = RegistryJson.read(persisted.get(), Map.class);
        assertEquals(9, reads.get());
        assertEquals("after", actual.get("name"));
        assertEquals(Map.of("SERVICE_ROUTE", false), actual.get("ruleEnabled"));
    }

    @Test
    @Timeout(value = 2000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void indexStopsConflictingMergeWhenConfiguredTimeoutExpires() throws Exception {
        // 存储持续拒绝 CAS；很短的既有超时应先于八次固定退避结束。
        ConfigService configs = this.configs;
        AtomicInteger writes = new AtomicInteger();
        when(configs.publishConfigCas(anyString(), eq(GROUP), anyString(), anyString())).thenAnswer(call -> {
            writes.incrementAndGet();
            return false;
        });
        NacosConfigStore store = new NacosConfigStore(configs, GROUP, 50);

        // deadline 到达后报告目录冲突，不无限重试或静默丢弃索引登记。
        ConcurrentModificationException failure = assertThrows(ConcurrentModificationException.class, //
                () -> store.index("applications", "composition-reader", "applications.composition-reader"));

        assertTrue(failure.getMessage().contains("index.applications.6"));
        assertTrue(writes.get() > 0 && writes.get() < 8);
    }

    @Test
    @Timeout(value = 2000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void updateStopsConflictingMergeWhenConfiguredTimeoutExpires() throws Exception {
        // 永久冲突仍然失败；内部自动合并的时间预算来自现有 timeoutMillis。
        ConfigService configs = this.configs;
        AtomicInteger writes = new AtomicInteger();
        when(configs.publishConfigCas(anyString(), eq(GROUP), anyString(), anyString())).thenAnswer(call -> {
            writes.incrementAndGet();
            return false;
        });
        NacosConfigStore store = new NacosConfigStore(configs, GROUP, 50);

        // deadline 到达后保留明确异常，不把自动合并失败当作更新成功。
        ConcurrentModificationException failure = assertThrows(ConcurrentModificationException.class, //
                () -> store.update("services.echo", Map.class, ignored -> Map.of("name", "after")));

        assertTrue(failure.getMessage().contains("services.echo"));
        assertTrue(writes.get() > 0 && writes.get() < 8);
    }
}
