/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.Arrays;
import java.util.concurrent.*;
import net.hasor.meteor.MetOptionParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class OptionInfoTest {
    @Test
    public void typedOptionsRetainStringRepresentation() {
        // 使用业务解析器写入类型值，字符串和类型入口读取同一份选项。
        OptionInfo options = new OptionInfo();
        MetOptionParser<Integer> parser = integerParser();
        options.addOption("count", 7, parser);

        assertEquals("7", options.getOption("count"));
        assertEquals(Integer.valueOf(7), options.getOption("count", parser));
    }

    @Test
    public void missingTypedOptionUsesParserDefault() {
        // 缺少选项时把空值交给解析器，由解析器决定默认值。
        OptionInfo options = new OptionInfo();
        assertEquals(Integer.valueOf(0), options.getOption("count", integerParser()));
    }

    @Test
    public void parserFailurePreservesRawAndUnrelatedOptions() {
        // 准备无法转换的值与无关选项。
        OptionInfo options = new OptionInfo();
        options.addOption("count", "invalid");
        options.addOption("ordinary", "unchanged");

        // 失败向调用者传播，既不改写原值，也不影响其他选项。
        assertThrows(NumberFormatException.class, () -> options.getOption("count", integerParser()));
        assertEquals("invalid", options.getOption("count"));
        assertEquals("unchanged", options.getOption("ordinary"));
    }

    @Test
    public void sharedCallOptionsObserveLateChanges() {
        // 响应直接关联同次调用的选项来源。
        OptionInfo payload = new OptionInfo();
        payload.addOption("header", "initial");
        OptionInfo response = new OptionInfo();
        response.setOptions(payload);

        // 流结束时补入的选项通过响应可见。
        payload.addOption("trailer", "finished");
        assertEquals("finished", response.getOption("trailer"));
        assertEquals(2, response.getOptionKeys().length);
    }

    @Test
    public void sharedCallOptionsForwardWritesAndRemovals() {
        // 关联响应选项后，修改与删除均作用在同一来源。
        OptionInfo payload = new OptionInfo();
        payload.addOption("header", "initial");
        payload.addOption("trailer", "finished");
        OptionInfo response = new OptionInfo();
        response.setOptions(payload);

        response.addOption("header", "updated");
        response.removeOption("trailer");
        assertEquals("updated", payload.getOption("header"));
        assertNull(payload.getOption("trailer"));
    }

    @Test
    public void copiedOptionsRemainIndependentFromSharedSource() {
        // 从代理选项复制出请求快照。
        OptionInfo payload = new OptionInfo();
        payload.addOption("header", "initial");
        OptionInfo response = new OptionInfo();
        response.setOptions(payload);
        OptionInfo copy = new OptionInfo();
        copy.addOptionMap(response);

        // 后续来源变化不写入快照。
        payload.addOption("trailer", "finished");
        response.addOption("header", "updated");
        assertNull(copy.getOption("trailer"));
        assertEquals("initial", copy.getOption("header"));
    }

    @Test
    public void nullKeyCanBeReadAndRemoved() {
        // 空键是合法选项键，读取和删除保持一致语义。
        OptionInfo options = new OptionInfo();
        options.addOption(null, "value");
        assertEquals("value", options.getOption(null));

        options.removeOption(null);
        assertEquals(0, options.getOptionKeys().length);
        assertNull(options.getOption(null));
    }

    @Test
    public void nullValueRemainsPresentUntilRemoved() {
        // 空值不等于删除，只有显式 remove 才移除键。
        OptionInfo options = new OptionInfo();
        options.addOption("nullable", null);
        assertTrue(Arrays.asList(options.getOptionKeys()).contains("nullable"));
        assertNull(options.getOption("nullable"));

        options.removeOption("nullable");
        assertEquals(0, options.getOptionKeys().length);
    }

    @Test
    public void copiedNullKeysAndValuesRemainIndependent() {
        // 复制保留空键、空值以及键的存在性。
        OptionInfo options = new OptionInfo();
        options.addOption(null, null);
        options.addOption("nullable", null);
        OptionInfo copy = new OptionInfo();
        copy.addOptionMap(options);
        assertEquals(2, copy.getOptionKeys().length);
        assertTrue(Arrays.asList(copy.getOptionKeys()).contains(null));
        assertNull(copy.getOption(null));
        assertNull(copy.getOption("nullable"));

        // 修改和删除副本不会污染原始配置。
        copy.addOption("nullable", "updated");
        assertEquals("updated", copy.getOption("nullable"));
        assertNull(options.getOption("nullable"));
        copy.removeOption(null);
        copy.removeOption("nullable");
        assertEquals(0, copy.getOptionKeys().length);
        assertNull(copy.getOption(null));
        assertEquals(2, options.getOptionKeys().length);
    }

    @Test
    public void concurrentWritersRetainIndependentUpdatesAndFinalValues() throws Exception {
        // 用开始信号协调并发写入，结束后验证最终配置。
        OptionInfo options = new OptionInfo();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?>[] writes = new Future<?>[2];
            for (int writer = 0; writer < writes.length; writer++) {
                String prefix = "writer-" + writer + "-";
                writes[writer] = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int i = 0; i < 1000; i++) {
                        String key = prefix + i;
                        options.addOption(key, "initial");
                        options.addOption(key, "final");
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> write : writes) {
                write.get(10, TimeUnit.SECONDS);
            }

            // 写入结束后，所有独立键都应保留，并能复制到最终值。
            OptionInfo copy = new OptionInfo();
            copy.addOptionMap(options);
            assertEquals(2000, copy.getOptionKeys().length);
            for (int writer = 0; writer < writes.length; writer++) {
                for (int i = 0; i < 1000; i++) {
                    assertEquals("final", copy.getOption("writer-" + writer + "-" + i));
                }
            }
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void sharedDefaultsSupportConcurrentMutationAndRequestCopiesIncludingNullValues() throws Exception {
        // 写线程修改共享默认值，读线程持续创建独立请求选项。
        OptionInfo defaults = new OptionInfo();
        defaults.addOption("nullable", null);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> writer = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 10000; i++) {
                    defaults.addOption("key-" + (i % 32), Integer.toString(i));
                    defaults.removeOption("key-" + ((i + 1) % 32));
                }
                return null;
            });
            Callable<Void> copy = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 3000; i++) {
                    OptionInfo request = new OptionInfo();
                    request.addOptionMap(defaults);
                    assertTrue(Arrays.asList(request.getOptionKeys()).contains("nullable"));
                    assertNull(request.getOption("nullable"));
                    request.addOption("request-only", "value");
                }
                return null;
            };
            Future<?> first = workers.submit(copy);
            Future<?> second = workers.submit(copy);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            // 有界等待全部任务完成，验证请求私有项不会反向写入默认配置。
            writer.get(10, TimeUnit.SECONDS);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertNull(defaults.getOption("request-only"));
            defaults.addOption("final", "value");
            assertEquals("value", defaults.getOption("final"));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private static MetOptionParser<Integer> integerParser() {
        return new MetOptionParser<>() {
            @Override
            public Integer parse(String value) {
                return value == null ? 0 : Integer.valueOf(value);
            }

            @Override
            public String format(Integer value) {
                return value.toString();
            }
        };
    }
}
