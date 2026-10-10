/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
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
import net.hasor.meteor.MetExtension;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExtensionLifecycleTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void resolvesServicesProvidedByAnExtension() throws Exception {
        MetConfiguration config = configuration(false);
        config.addExtension(Recording::new);
        try (MetContext context = config.buildContext()) {
            Recording extension = context.getExtension(Recording.class);
            assertSame(extension.service, context.getExtension(AtomicInteger.class));
            assertSame(extension.service, context.getExtension(AtomicInteger.class));
            assertNull(context.getExtension(CharSequence.class));
            context.close();
            assertNull(context.getExtension(AtomicInteger.class));
        }
    }

    @Test
    public void rejectsAmbiguousServicesFromDifferentExtensions() throws Exception {
        MetConfiguration config = configuration(false);
        config.addExtension(Recording::new);
        config.addExtension(Recording::new);
        try (MetContext context = config.buildContext()) {
            assertThrows(IllegalStateException.class, () -> context.getExtension(AtomicInteger.class));
        }
    }

    @Test
    public void createsOncePerContextAndDeliversLifecycleInOrder() throws Exception {
        MetConfiguration config = configuration(true);
        config.addExtension(context -> new Recording(context));
        try (MetContext context = config.buildContext()) {
            Recording extension = context.getExtension(Recording.class);
            assertEquals(List.of("create"), extension.calls);
            assertNull(context.bindAddress("rsf"));
            context.publisher().rsfService(Echo.class, value -> value).register();
            context.start();
            context.start();
            assertEquals(List.of("create", "beforeStart", "afterStart"), extension.calls);
            context.offline();
            context.online();
            context.publisher().rsfService(Echo.class).name("late").register();
            assertEquals(List.of(MetContextEvent.OFFLINE, MetContextEvent.ONLINE, MetContextEvent.SERVICES_CHANGED), extension.events);
            context.close();
            context.close();
            assertEquals(List.of("create", "beforeStart", "afterStart", "close"), extension.calls);
            assertNull(context.getExtension(Recording.class));
        }
    }

    @Test
    public void reusableConfigurationCreatesIndependentRuntimeObjects() throws Exception {
        MetConfiguration config = configuration(false);
        config.addExtension(Recording::new);
        try (MetContext first = config.buildContext(); MetContext second = config.buildContext()) {
            Recording one = first.getExtension(Recording.class);
            Recording two = second.getExtension(Recording.class);
            assertNotSame(one, two);
            assertSame(first, one.context);
            assertSame(second, two.context);
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
        IllegalStateException failure = assertThrows(IllegalStateException.class, config::buildContext);
        assertSame(expected, failure.getCause());
        assertEquals(List.of("create", "close"), installed.get().calls);
        assertThrows(IllegalStateException.class, installed.get().context::start);
    }

    @Test
    public void beforeStartFailureClosesAllInstalledExtensionsWithoutListening() throws Exception {
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
            IllegalStateException failure = assertThrows(IllegalStateException.class, context::start);
            assertSame(expected, failure.getCause());
            assertNull(context.bindAddress("rsf"));
            assertEquals(List.of("create", "beforeStart", "close"), installed.get(0).calls);
            assertEquals(List.of("create", "close"), installed.get(1).calls);
        }
    }

    @Test
    public void afterStartFailurePreservesCauseAndReleasesListenerDespiteCleanupFailure() throws Exception {
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
            IllegalStateException failure = assertThrows(IllegalStateException.class, context::start);
            assertSame(startup, failure.getCause());
            assertSame(cleanup, startup.getSuppressed()[0].getCause());
            assertEquals(List.of("create", "beforeStart", "close"), tail.get().calls);
            assertTrue(port.get() > 0);
            assertNull(context.bindAddress("rsf"));
            try (ServerSocket available = new ServerSocket(port.get())) {
                assertEquals(port.get(), available.getLocalPort());
            }
        }
    }

    @Test
    public void closeBeforeStartAndNormalCloseReleaseEveryExtension() throws Exception {
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
        MetContext context = config.buildContext();
        IllegalStateException failure = assertThrows(IllegalStateException.class, context::close);
        assertSame(installed.get(0).closeFailure, failure.getCause());
        assertSame(installed.get(1).closeFailure, failure.getCause().getSuppressed()[0]);
        for (Recording extension : installed) {
            assertEquals(List.of("create", "close"), extension.calls);
        }
        context.close();
        assertThrows(IllegalStateException.class, context::start);
    }

    @Test
    public void hooksDoNotHoldContextMonitorAndCloseWaitsForStartup() throws Exception {
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
        }

        @Override
        public <T> T getService(Class<T> type) {
            return type == AtomicInteger.class ? type.cast(this.service) : null;
        }

        @Override
        public void beforeStart() throws Exception {
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
        public void onEvent(MetContextEvent event) {
            this.events.add(event);
        }

        @Override
        public void close() throws Exception {
            assertFalse(Thread.holdsLock(this.context));
            this.calls.add("close");
            if (this.closeFailure != null) {
                throw this.closeFailure;
            }
        }
    }
}
