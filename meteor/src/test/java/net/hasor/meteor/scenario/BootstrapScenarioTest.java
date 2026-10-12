/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario;
import java.util.ArrayList;
import java.util.List;
import net.hasor.meteor.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 从配置、服务声明到上下文关闭的最小应用。 */
public class BootstrapScenarioTest {
    @Test
    public void publishAnInterfaceAndControlApplicationLifecycle() throws Exception {
        // 配置监听和手动上线；构建本身不占用端口。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setApplicationName("greeting-server");
        configuration.setAutomaticOnline(false);
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        List<MetContextEventType> events = new ArrayList<>();
        try (MetContext context = configuration.buildContext()) {
            context.subscribe(event -> events.add(event.getType()));
            assertNull(context.bindAddress("rsf"));

            // 注解定义服务身份，别名和元数据通过发布 API 补充。
            var publication = context.publisher().rsfService(Greeting.class, value -> "hello " + value);
            publication.aliasName("demo", "greeting");
            MetBindInfo<Greeting> service = publication.register();
            service.setMetaData("owner", "examples");
            assertEquals("demo", service.getBindGroup());
            assertEquals("greeting", service.getBindName());
            assertEquals("1", service.getBindVersion());
            assertSame(service, context.getServiceInfo("demo", "greeting"));
            assertEquals("examples", context.getServiceInfo(service.getBindID()).getMetaData("owner"));

            // 启动完成后才能上线；同进程调用使用已发布的实现。
            context.start();
            assertFalse(context.isOnline());
            assertTrue(context.bindAddress("rsf").getPort() > 0);
            context.online();
            assertTrue(context.isOnline());
            Greeting local = context.getRsfClient().getRemote(service);
            assertEquals("hello Meteor", local.greet("Meteor"));
            context.offline();
            assertFalse(context.isOnline());
        }

        // try-with-resources 负责关闭，事件体现已完成的生命周期动作。
        List<MetContextEventType> expected = List.of(MetContextEventType.REGISTERED, MetContextEventType.STARTED, MetContextEventType.ONLINE, MetContextEventType.OFFLINE, MetContextEventType.CLOSED);
        assertEquals(expected, events);
    }

    @Test
    public void reusableConfigurationCreatesIndependentContextsAndExtensions() throws Exception {
        // 扩展由配置创建，每个上下文拥有自己的扩展实例。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setApplicationName("first");
        configuration.setRequestOption("tenant", "default");
        configuration.connector("tcp").protocol("rsf");
        configuration.addExtension(context -> new AuditExtension());
        try (MetContext first = configuration.buildContext()) {
            configuration.setApplicationName("second");
            try (MetContext second = configuration.buildContext()) {
                AuditExtension one = first.getExtension(AuditExtension.class);
                AuditExtension two = second.getExtension(AuditExtension.class);
                assertNotSame(one, two);
                assertEquals("first", first.getApplicationName());
                assertEquals("second", second.getApplicationName());

                // 运行期选项仅影响当前上下文，客户端不配置 bind 就不监听。
                first.getSettings().getRequestOptions().addOption("tenant", "first");
                assertEquals("default", second.getSettings().getRequestOptions().getOption("tenant"));
                first.start();
                second.start();
                assertNull(first.bindAddress("rsf"));
                assertEquals(List.of("beforeStart", "afterStart"), one.steps);
                first.close();
                assertEquals(List.of("beforeStart", "afterStart", "close"), one.steps);
                assertEquals(List.of("beforeStart", "afterStart"), two.steps);
                assertTrue(second.isOnline());
            }
        }
    }

    @Test
    public void invalidProtocolSelectionFailsDuringBootstrap() {
        // 名称选择 SPI 能力；不存在的协议在构建时反馈，而不是启动后才失败。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("missing-demo-protocol");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::buildContext);
        assertTrue(failure.getMessage().contains("missing-demo-protocol"));
    }

    @MetService(group = "demo", name = "greeting", version = "1")
    public interface Greeting {
        String greet(String name);
    }

    private static final class AuditExtension implements MetExtension {
        private final List<String> steps = new ArrayList<>();

        public <T> T getService(Class<T> type) {
            return null;
        }

        public void beforeStart() {
            this.steps.add("beforeStart");
        }

        public void afterStart() {
            this.steps.add("afterStart");
        }

        public void close() {
            this.steps.add("close");
        }
    }
}
