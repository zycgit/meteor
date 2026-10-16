/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.bootstrap;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetContextEvent;
import net.hasor.meteor.MetContextEventType;
import net.hasor.meteor.MetExtension;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExtensionLifecycleTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void resolvesServicesProvidedByAnExtension() throws Exception {
        // 扩展工厂通过公开配置安装服务，同一上下文的重复查询复用该服务。
        MetConfiguration config = configuration(false);
        config.addExtension(Recording::new);
        try (MetContext context = config.buildContext()) {
            Recording extension = context.getExtension(Recording.class);
            assertSame(extension.service, context.getExtension(AtomicInteger.class));
            assertSame(extension.service, context.getExtension(AtomicInteger.class));
            assertNull(context.getExtension(CharSequence.class));

            // 关闭后扩展服务不再可查询。
            context.close();
            assertNull(context.getExtension(AtomicInteger.class));
        }
    }

    @Test
    public void rejectsAmbiguousServicesFromDifferentExtensions() throws Exception {
        // 两个独立扩展提供相同服务类型，查询必须明确拒绝歧义。
        MetConfiguration config = configuration(false);
        config.addExtension(Recording::new);
        config.addExtension(Recording::new);
        try (MetContext context = config.buildContext()) {
            assertThrows(IllegalStateException.class, () -> context.getExtension(AtomicInteger.class));
        }
    }

    @Test
    public void createsOncePerContextAndStartsExtensionsInOrder() throws Exception {
        // 配置监听地址；构建只创建扩展，不提前启动监听。
        MetConfiguration config = configuration(true);
        config.addExtension(Recording::new);
        try (MetContext context = config.buildContext()) {
            Recording extension = context.getExtension(Recording.class);
            assertEquals(List.of("create"), extension.calls);
            assertNull(context.bindAddress("rsf"));
            context.publisher().rsfService(Echo.class, value -> value).register();

            // 首次启动按顺序通知扩展，重复启动不重复执行钩子。
            context.start();
            context.start();
            assertEquals(List.of("create", "beforeStart", "afterStart"), extension.calls);
        }
    }

    @Test
    public void runtimeChangesNotifyTheExistingExtensionInOrder() throws Exception {
        // 扩展在构建时订阅事件，启动前后登记的服务都应按发生顺序通知。
        MetConfiguration config = configuration(true);
        config.addExtension(Recording::new);
        try (MetContext context = config.buildContext()) {
            Recording extension = context.getExtension(Recording.class);
            context.publisher().rsfService(Echo.class, value -> value).register();
            context.start();

            // 上下线和运行期登记不重新创建扩展，也不重跑启动钩子。
            context.offline();
            context.online();
            context.publisher().rsfService(Echo.class).name("late").register();
            List<MetContextEventType> types = extension.events.stream().map(MetContextEvent::getType).toList();
            List<MetContextEventType> expected = List.of( //
                    MetContextEventType.REGISTERED, MetContextEventType.STARTED, //
                    MetContextEventType.OFFLINE, MetContextEventType.ONLINE, MetContextEventType.REGISTERED);
            assertEquals(expected, types);
            assertSame(extension, context.getExtension(Recording.class));
            assertEquals(List.of("create", "beforeStart", "afterStart"), extension.calls);
        }
    }

    @Test
    public void repeatedCloseReleasesTheStartedExtensionOnlyOnce() throws Exception {
        // 已启动的扩展收到完整的创建和启动钩子。
        MetConfiguration config = configuration(true);
        config.addExtension(Recording::new);
        try (MetContext context = config.buildContext()) {
            Recording extension = context.getExtension(Recording.class);
            context.start();

            // 重复关闭只释放一次，扩展查询失效，并仅收到一次关闭事件。
            context.close();
            context.close();
            assertEquals(List.of("create", "beforeStart", "afterStart", "close"), extension.calls);
            assertNull(context.getExtension(Recording.class));
            List<MetContextEventType> types = extension.events.stream().map(MetContextEvent::getType).toList();
            assertEquals(List.of(MetContextEventType.STARTED, MetContextEventType.CLOSED), types);
        }
    }

    @Test
    public void reentrantCloseReleasesExtensionsAfterClosedEventDelivery() throws Exception {
        // 手动上线期间由首个 ONLINE 监听器重入关闭上下文。
        List<String> order = new ArrayList<>();
        MetConfiguration config = configuration(false);
        config.setAutomaticOnline(false);
        config.addExtension(context -> new CloseOrderExtension(order));
        try (MetContext context = config.buildContext()) {
            context.subscribe(event -> {
                if (event.getType() == MetContextEventType.ONLINE) {
                    order.add("online-first");
                    try {
                        context.close();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    order.add("close-returned");
                } else if (event.getType() == MetContextEventType.CLOSED) {
                    order.add("closed-first");
                }
            });
            context.subscribe(event -> {
                if (event.getType() == MetContextEventType.ONLINE) {
                    order.add("online-second");
                } else if (event.getType() == MetContextEventType.CLOSED) {
                    order.add("closed-second");
                }
            });

            // 重入 close 先返回，当前 ONLINE 和排队的 CLOSED 均完成后才关闭扩展。
            context.start();
            context.online();
            List<String> expected = List.of( //
                    "online-first", "close-returned", "online-second", //
                    "closed-first", "closed-second", "extension-close");
            assertEquals(expected, order);
        }
    }

    @Test
    public void reusableConfigurationCreatesIndependentRuntimeObjects() throws Exception {
        // 复用配置构建两个上下文，每个上下文创建自己的扩展实例。
        MetConfiguration config = configuration(false);
        config.addExtension(Recording::new);
        try (MetContext first = config.buildContext(); //
             MetContext second = config.buildContext()) {
            Recording one = first.getExtension(Recording.class);
            Recording two = second.getExtension(Recording.class);
            assertNotSame(one, two);
            assertSame(first, one.context);
            assertSame(second, two.context);

            // 关闭第一个上下文不提前启动或关闭第二个，后者仍能正常启动。
            first.start();
            first.close();
            assertEquals(List.of("create"), two.calls);
            second.start();
            assertTrue(second.isOnline());
            assertEquals(List.of("create", "beforeStart", "afterStart"), two.calls);
        }
    }

    @Test
    public void creationFailureClosesPreviousExtensionsAndCore() {
        // 第二个扩展创建失败时，先前安装的扩展和内核都必须回收。
        AtomicReference<Recording> installed = new AtomicReference<>();
        IOException expected = new IOException("create failed");
        MetConfiguration config = configuration(false);
        config.addExtension(context -> {
            Recording recording = new Recording(context);
            installed.set(recording);
            return recording;
        });
        config.addExtension(context -> {
            throw expected;
        });

        // 构建入口暴露原创建异常，并使已创建上下文不可重新启动。
        IllegalStateException failure = assertThrows(IllegalStateException.class, config::buildContext);
        assertSame(expected, failure.getCause());
        Recording previous = installed.get();
        assertEquals(List.of("create", "close"), previous.calls);
        assertThrows(IllegalStateException.class, previous.context::start);
    }

    @Test
    public void beforeStartFailureClosesAllInstalledExtensionsWithoutListening() throws Exception {
        // 首个扩展在监听开始前失败，未执行钩子的后续扩展也须关闭。
        IOException expected = new IOException("prepare failed");
        MetConfiguration config = configuration(true);
        List<Recording> installed = new ArrayList<>();
        config.addExtension(context -> {
            Recording recording = new Recording(context);
            recording.beforeFailure = expected;
            installed.add(recording);
            return recording;
        });
        config.addExtension(context -> {
            Recording recording = new Recording(context);
            installed.add(recording);
            return recording;
        });
        try (MetContext context = config.buildContext()) {
            // 启动错误直接返回给调用者，并执行完整的失败回收。
            IllegalStateException failure = assertThrows(IllegalStateException.class, context::start);
            assertSame(expected, failure.getCause());
            assertNull(context.bindAddress("rsf"));
            assertEquals(List.of("create", "beforeStart", "close"), installed.get(0).calls);
            assertEquals(List.of("create", "close"), installed.get(1).calls);
        }
    }

    @Test
    public void afterStartFailurePreservesCauseAndReleasesListenerDespiteCleanupFailure() throws Exception {
        // 启动后钩子失败并叠加清理错误，验证原始原因和监听端口释放。
        IOException startup = new IOException("publication failed");
        IOException cleanup = new IOException("cleanup failed");
        AtomicInteger port = new AtomicInteger();
        AtomicReference<Recording> tail = new AtomicReference<>();
        MetConfiguration config = configuration(true);
        config.addExtension(context -> {
            Recording recording = new Recording(context);
            recording.afterFailure = startup;
            recording.closeFailure = cleanup;
            recording.boundPort = port;
            return recording;
        });
        config.addExtension(context -> {
            Recording recording = new Recording(context);
            tail.set(recording);
            return recording;
        });
        try (MetContext context = config.buildContext()) {
            // 启动错误直接返回给调用者，并执行完整的失败回收。
            IllegalStateException failure = assertThrows(IllegalStateException.class, context::start);
            assertSame(startup, failure.getCause());
            Throwable suppressedCleanup = startup.getSuppressed()[0].getCause();
            assertSame(cleanup, suppressedCleanup);
            assertEquals(List.of("create", "beforeStart", "close"), tail.get().calls);
            assertTrue(port.get() > 0);
            assertNull(context.bindAddress("rsf"));

            // 使用独立套接字重新绑定实际监听端口，证明底层监听已经释放。
            try (ServerSocket available = new ServerSocket(port.get())) {
                assertEquals(port.get(), available.getLocalPort());
            }
        }
    }

    @Test
    public void closeBeforeStartReleasesEveryExtensionDespiteCleanupFailures() throws Exception {
        // 两个扩展关闭时都抛异常，未启动的上下文仍须尝试释放所有扩展。
        MetConfiguration config = configuration(false);
        List<Recording> installed = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            config.addExtension(context -> {
                Recording recording = new Recording(context);
                recording.closeFailure = new IOException("close failed");
                installed.add(recording);
                return recording;
            });
        }

        try (MetContext context = config.buildContext()) {
            // 首个异常保留为原因，后续清理失败附加到同一原因上。
            IllegalStateException failure = assertThrows(IllegalStateException.class, context::close);
            Throwable primary = failure.getCause();
            assertSame(installed.get(0).closeFailure, primary);
            assertSame(installed.get(1).closeFailure, primary.getSuppressed()[0]);
            for (Recording extension : installed) {
                assertEquals(List.of("create", "close"), extension.calls);
            }

            // 即使关闭报错，关闭状态仍已提交：重复关闭安全且不能重新启动。
            context.close();
            assertThrows(IllegalStateException.class, context::start);
        }
    }

    @Test
    public void hooksDoNotHoldContextMonitorAndCloseWaitsForStartup() throws Exception {
        // 扩展启动钩子暂停时，另一线程仍能订阅，关闭则等待启动结束。
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        MetConfiguration config = configuration(false);
        AtomicReference<Recording> installed = new AtomicReference<>();
        config.addExtension(context -> {
            Recording recording = new Recording(context);
            recording.entered = entered;
            recording.proceed = proceed;
            installed.set(recording);
            return recording;
        });
        try (MetContext context = config.buildContext()) {
            var starting = workers.submit(context::start);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // 扩展启动期间，其他线程仍然能够注册事件监听。
            workers.submit(() -> context.subscribe(event -> {
            })).get(5, TimeUnit.SECONDS);

            // 关闭与尚未完成的启动并发，最终仍保持钩子的生命周期顺序。
            var closing = workers.submit(() -> {
                context.close();
                return null;
            });
            proceed.countDown();
            starting.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("create", "beforeStart", "afterStart", "close"), installed.get().calls);
        } finally {
            proceed.countDown();
            workers.shutdownNow();
        }
    }

    private static MetConfiguration configuration(boolean listen) {
        MetConfiguration config = new MetConfiguration();
        MetConfiguration.ConnectorSettings tcp = config.connector("tcp");
        if (listen) {
            tcp.bind("127.0.0.1", 0);
        }
        tcp.protocol("rsf");
        return config;
    }

    private static final class Recording implements MetExtension {
        private final AtomicInteger         service = new AtomicInteger();
        private final MetContext            context;
        private final List<String>          calls   = new ArrayList<>();
        private final List<MetContextEvent> events  = new ArrayList<>();
        private       IOException           beforeFailure;
        private       IOException           afterFailure;
        private       IOException           closeFailure;
        private       AtomicInteger         boundPort;
        private       CountDownLatch        entered;
        private       CountDownLatch        proceed;

        private Recording(MetContext context) {
            this.context = context;
            this.calls.add("create");
            this.context.subscribe(this.events::add);
        }

        @Override
        public <T> T getService(Class<T> type) {
            return type == AtomicInteger.class ? type.cast(this.service) : null;
        }

        @Override
        public void beforeStart() throws Exception {
            // 记录启动前的锁和监听边界，再按场景需要阻塞或注入失败。
            assertFalse(Thread.holdsLock(this.context));
            assertNull(this.context.bindAddress("rsf"));
            this.calls.add("beforeStart");
            if (this.entered != null) {
                this.entered.countDown();
                assertTrue(this.proceed.await(5, TimeUnit.SECONDS));
            }
            if (this.beforeFailure != null) {
                throw this.beforeFailure;
            }
        }

        @Override
        public void afterStart() throws Exception {
            // 启动后读取真实监听端口，供失败回收场景验证端口释放。
            assertFalse(Thread.holdsLock(this.context));
            this.calls.add("afterStart");
            if (this.boundPort != null) {
                this.boundPort.set(this.context.bindAddress("rsf").getPort());
            }
            if (this.afterFailure != null) {
                throw this.afterFailure;
            }
        }

        @Override
        public void close() throws Exception {
            // 即使注入清理失败，也先记录本扩展确实进入了关闭钩子。
            assertFalse(Thread.holdsLock(this.context));
            this.calls.add("close");
            if (this.closeFailure != null) {
                throw this.closeFailure;
            }
        }
    }

    private record CloseOrderExtension(List<String> order) implements MetExtension {

        @Override
        public <T> T getService(Class<T> type) {
            return null;
        }

        @Override
        public void beforeStart() {
        }

        @Override
        public void afterStart() {
        }

        @Override
        public void close() {
            this.order.add("extension-close");
        }
    }
}
