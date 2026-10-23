/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.metadata;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.curator.test.TestingServer;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.metadata.MappingChangedEvent;
import org.apache.dubbo.metadata.MappingListener;
import org.apache.dubbo.remoting.zookeeper.curator5.ZookeeperClientManager;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.Test;
import static org.junit.Assert.*;

public class DubboZookeeperMetadataReportTest {
    private static final String SERVICE = "net.hasor.example.registry.dubbo.LateMappingService";

    @Test
    public void twoApplicationsWatchTheSameMappingIndependently() throws Exception {
        // 两个独占 SDK 应用在同一注册中心订阅完全相同的原生映射路径。
        FrameworkModel framework = new FrameworkModel();
        try (TestingServer server = new TestingServer()) {
            ApplicationModel first = application(framework, "mapping-first");
            ApplicationModel second = application(framework, "mapping-second");
            URL firstAddress = address(server, first);
            URL secondAddress = address(server, second);
            DubboZookeeperMetadataReport firstReport = report(firstAddress, first);
            DubboZookeeperMetadataReport secondReport = report(secondAddress, second);
            try {
                BlockingQueue<MappingChangedEvent> firstEvents = new LinkedBlockingQueue<>();
                BlockingQueue<MappingChangedEvent> secondEvents = new LinkedBlockingQueue<>();
                assertTrue(firstReport.getServiceAppMapping(SERVICE, listener(firstEvents), firstAddress).isEmpty());
                assertTrue(secondReport.getServiceAppMapping(SERVICE, listener(secondEvents), secondAddress).isEmpty());

                // 路径随后才创建，两个应用都必须收到通知，不能命中其他应用的静态缓存。
                assertTrue(firstReport.registerServiceAppMapping(SERVICE, "mapping", "late-provider", null));
                MappingChangedEvent firstEvent = firstEvents.poll(5, TimeUnit.SECONDS);
                MappingChangedEvent secondEvent = secondEvents.poll(5, TimeUnit.SECONDS);
                assertNotNull(firstEvent);
                assertNotNull(secondEvent);
                assertEquals(Set.of("late-provider"), firstEvent.getApps());
                assertEquals(Set.of("late-provider"), secondEvent.getApps());
            } finally {
                firstReport.destroy();
                secondReport.destroy();
            }
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void differentRegistriesDoNotShareTheSameMappingPath() throws Exception {
        // 两个注册中心使用相同服务名和映射路径，数据与监听仍分别属于各自连接。
        FrameworkModel framework = new FrameworkModel();
        try (TestingServer firstServer = new TestingServer(); //
             TestingServer secondServer = new TestingServer()) {
            ApplicationModel first = application(framework, "mapping-first-registry");
            ApplicationModel second = application(framework, "mapping-second-registry");
            URL firstAddress = address(firstServer, first);
            URL secondAddress = address(secondServer, second);
            DubboZookeeperMetadataReport firstReport = report(firstAddress, first);
            DubboZookeeperMetadataReport secondReport = report(secondAddress, second);
            try {
                BlockingQueue<MappingChangedEvent> firstEvents = new LinkedBlockingQueue<>();
                BlockingQueue<MappingChangedEvent> secondEvents = new LinkedBlockingQueue<>();
                firstReport.getServiceAppMapping(SERVICE, listener(firstEvents), firstAddress);
                secondReport.getServiceAppMapping(SERVICE, listener(secondEvents), secondAddress);

                // 第一注册中心的通知只交给第一个应用。
                assertTrue(firstReport.registerServiceAppMapping(SERVICE, "mapping", "first-provider", null));
                MappingChangedEvent firstEvent = firstEvents.poll(5, TimeUnit.SECONDS);
                assertNotNull(firstEvent);
                assertEquals(Set.of("first-provider"), firstEvent.getApps());
                assertNull(secondEvents.poll(300, TimeUnit.MILLISECONDS));

                // 第二注册中心随后创建相同路径，仍能建立自己的真实监听。
                assertTrue(secondReport.registerServiceAppMapping(SERVICE, "mapping", "second-provider", null));
                MappingChangedEvent secondEvent = secondEvents.poll(5, TimeUnit.SECONDS);
                assertNotNull(secondEvent);
                assertEquals(Set.of("second-provider"), secondEvent.getApps());
                assertEquals(Set.of("first-provider"), firstReport.getServiceAppMapping(SERVICE, firstAddress));
            } finally {
                firstReport.destroy();
                secondReport.destroy();
            }
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void closingOneReportDoesNotStopAnotherMappingWatcher() throws Exception {
        // 同一路径有两个独立报告对象，先关闭第一个对象持有的监听连接。
        FrameworkModel framework = new FrameworkModel();
        try (TestingServer server = new TestingServer()) {
            ApplicationModel first = application(framework, "mapping-closing");
            ApplicationModel second = application(framework, "mapping-surviving");
            URL firstAddress = address(server, first);
            URL secondAddress = address(server, second);
            DubboZookeeperMetadataReport firstReport = report(firstAddress, first);
            DubboZookeeperMetadataReport secondReport = report(secondAddress, second);
            try {
                BlockingQueue<MappingChangedEvent> firstEvents = new LinkedBlockingQueue<>();
                BlockingQueue<MappingChangedEvent> secondEvents = new LinkedBlockingQueue<>();
                firstReport.getServiceAppMapping(SERVICE, listener(firstEvents), firstAddress);
                secondReport.getServiceAppMapping(SERVICE, listener(secondEvents), secondAddress);
                firstReport.destroy();

                // 另一应用仍能收到随后创建的映射，借用的 SDK 执行器没有被关闭。
                assertTrue(secondReport.registerServiceAppMapping(SERVICE, "mapping", "surviving-provider", null));
                MappingChangedEvent secondEvent = secondEvents.poll(5, TimeUnit.SECONDS);
                assertNotNull(secondEvent);
                assertEquals(Set.of("surviving-provider"), secondEvent.getApps());
                assertNull(firstEvents.poll(300, TimeUnit.MILLISECONDS));
            } finally {
                firstReport.destroy();
                secondReport.destroy();
            }
        } finally {
            framework.destroy();
        }
    }

    @Test
    public void mappingNotificationRunsOnTheSdkMappingExecutor() throws Exception {
        // 真实节点变更应交给本 framework 的执行器，不能在 ZooKeeper EventThread 初始化实例缓存。
        FrameworkModel framework = new FrameworkModel();
        try (TestingServer server = new TestingServer()) {
            ApplicationModel application = application(framework, "mapping-callback-thread");
            URL address = address(server, application);
            DubboZookeeperMetadataReport report = report(address, application);
            try {
                BlockingQueue<MappingChangedEvent> events = new LinkedBlockingQueue<>();
                AtomicReference<Thread> callback = new AtomicReference<>();
                MappingListener listener = new MappingListener() {
                    @Override
                    public void onEvent(MappingChangedEvent event) {
                        callback.set(Thread.currentThread());
                        events.add(event);
                    }

                    @Override
                    public void stop() {
                    }
                };
                report.getServiceAppMapping(SERVICE, listener, address);

                // 此处仍由官方报告对象写入，验证实际异步分派线程。
                assertTrue(report.registerServiceAppMapping(SERVICE, "mapping", "async-provider", null));
                assertNotNull(events.poll(5, TimeUnit.SECONDS));
                String thread = callback.get().getName();
                assertTrue(thread, thread.startsWith("Dubbo-framework-mapping-refreshing-scheduler"));
            } finally {
                report.destroy();
            }
        } finally {
            framework.destroy();
        }
    }

    private static MappingListener listener(BlockingQueue<MappingChangedEvent> events) {
        return new MappingListener() {
            @Override
            public void onEvent(MappingChangedEvent event) {
                events.add(event);
            }

            @Override
            public void stop() {
            }
        };
    }

    private static ApplicationModel application(FrameworkModel framework, String name) {
        ApplicationModel application = framework.newApplication();
        ApplicationConfig configuration = new ApplicationConfig(application);
        configuration.setName(name);
        configuration.setQosEnable(false);
        configuration.setEnableFileCache(false);
        application.getApplicationConfigManager().setApplication(configuration);
        return application;
    }

    private static URL address(TestingServer server, ApplicationModel application) {
        return URL.valueOf("zookeeper://" + server.getConnectString()) //
                .addParameter("sync-report", true).setScopeModel(application);
    }

    private static DubboZookeeperMetadataReport report(URL address, ApplicationModel application) {
        return new DubboZookeeperMetadataReport(address, ZookeeperClientManager.getInstance(application));
    }
}
