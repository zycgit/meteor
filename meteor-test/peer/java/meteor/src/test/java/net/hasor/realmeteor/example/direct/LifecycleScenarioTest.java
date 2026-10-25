/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import java.util.ArrayList;
import java.util.List;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetContextEventType;
import net.hasor.meteor.MetExtension;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 生命周期、配置快照及扩展所有权，通过公开 bootstrap 入口验证。 */
public class LifecycleScenarioTest {
    @Test(timeout = 30000)
    public void manualOnlineAndOfflineControlTheApplicationListeners() throws Exception {
        MetConfiguration configuration = DirectRpcExample.server("127.0.0.1", 0, 0);
        configuration.setAutomaticOnline(false);
        List<MetContextEventType> events = new ArrayList<>();
        try (MetContext context = configuration.buildContext()) {
            // 构建完成后订阅生命周期；尚未 start 的实例不占用监听端口。
            context.subscribe(event -> events.add(event.getType()));
            assertNull(context.getListenAddress("rsf"));
            context.start();
            assertFalse(context.isOnline());
            assertNull(context.getListenAddress("rsf"));

            // 业务发布完成后由应用决定何时对外开放，内置服务不需要再注册。
            MetBindInfo<OrderDemoService> binding = context.publisher() //
                    .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("rsf").register();
            context.online();
            assertTrue(context.isOnline());
            assertTrue(context.getListenAddress("rsf").getPort() > 0);
            Order order = new Order();
            order.setId(701);
            order.setDescription("ready before online");
            Order result = context.getRsfClient().getRemote(binding).save(order);
            assertEquals("ready before online", result.getDescription());

            // 离线撤销监听；Context 的创建者通过 try-with-resources 负责最终关闭。
            context.offline();
            assertFalse(context.isOnline());
            assertNull(context.getListenAddress("rsf"));
        }
        List<MetContextEventType> expected = List.of(MetContextEventType.STARTED, //
                MetContextEventType.REGISTERED, MetContextEventType.ONLINE, //
                MetContextEventType.OFFLINE, MetContextEventType.CLOSED);
        assertEquals(expected, events);
    }

    @Test(timeout = 30000)
    public void reusingConfigurationCreatesIndependentSettingsSnapshots() throws Exception {
        MetConfiguration configuration = DirectRpcExample.client("rsf");
        configuration.setApplicationName("first").setRequestOption("tenant", "original");
        try (MetContext first = configuration.buildContext()) {
            // 修改配置只影响此后构建的上下文，先前构建的设置保持不变。
            configuration.setApplicationName("second").setRequestOption("tenant", "changed");
            try (MetContext second = configuration.buildContext()) {
                first.start();
                second.start();
                assertEquals("first", first.getApplicationName());
                assertEquals("second", second.getApplicationName());
                assertEquals("original", first.getSettings().getRequestOptions().getOption("tenant"));
                assertEquals("changed", second.getSettings().getRequestOptions().getOption("tenant"));

                // 两个消费者均只配置出口，不因复用配置而建立监听。
                assertNull(first.getListenAddress("rsf"));
                assertNull(second.getListenAddress("rsf"));
            }
        }
    }

    @Test(timeout = 30000)
    public void builtContextSettingsAreReadOnly() throws Exception {
        MetConfiguration configuration = DirectRpcExample.client("rsf");
        configuration.setRequestOption("tenant", "configured");
        try (MetContext context = configuration.buildContext()) {
            // 运行期读取设置，不直接修改构建阶段已经固化的选项。
            context.start();
            assertThrows(UnsupportedOperationException.class, () -> {
                context.getSettings().getRequestOptions().addOption("tenant", "unexpected");
            });
            assertEquals("configured", context.getSettings().getRequestOptions().getOption("tenant"));
        }
    }

    @Test(timeout = 30000)
    public void eachContextOwnsItsExtensionInstanceAndLifecycle() throws Exception {
        MetConfiguration configuration = DirectRpcExample.client("rsf");
        configuration.addExtension(context -> new LifecycleProbe());
        try (MetContext first = configuration.buildContext(); //
             MetContext second = configuration.buildContext()) {
            // 工厂为每个上下文创建独立扩展；测试探针不进入正式示例产物。
            LifecycleProbe one = first.getExtension(LifecycleProbe.class);
            LifecycleProbe two = second.getExtension(LifecycleProbe.class);
            assertNotSame(one, two);
            first.start();
            second.start();
            assertEquals(List.of("beforeStart", "afterStart"), one.steps());
            assertEquals(List.of("beforeStart", "afterStart"), two.steps());

            // 关闭一个上下文只关闭它持有的扩展，不影响另一个运行中的实例。
            first.close();
            assertEquals(List.of("beforeStart", "afterStart", "close"), one.steps());
            assertEquals(List.of("beforeStart", "afterStart"), two.steps());
            assertTrue(second.isOnline());
        }
    }

    @Test(timeout = 30000)
    public void anUnknownProtocolFailsWhileBuildingTheContext() {
        // 名称选择已有 SPI 能力，不存在的名称在 bootstrap 阶段直接报错。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("missing-demo-protocol");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::buildContext);
        assertTrue(failure.getMessage().contains("missing-demo-protocol"));
    }

    private static final class LifecycleProbe implements MetExtension {
        private final List<String> steps = new ArrayList<>();

        @Override
        public <T> T getService(Class<T> type) {
            return null;
        }

        @Override
        public void beforeStart() {
            this.steps.add("beforeStart");
        }

        @Override
        public void afterStart() {
            this.steps.add("afterStart");
        }

        @Override
        public void close() {
            this.steps.add("close");
        }

        private List<String> steps() {
            return List.copyOf(this.steps);
        }
    }
}
