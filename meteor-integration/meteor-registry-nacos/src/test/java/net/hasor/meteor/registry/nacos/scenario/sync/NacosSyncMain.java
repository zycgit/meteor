/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos.scenario.sync;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.ObjectUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.nacos.scenario.AbstractNacosExample;
import net.hasor.meteor.registry.nacos.scenario.EchoService;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.RefServiceRelationData;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import static org.junit.Assert.*;

/** 全量推送、全量拉取、单服务推拉和失败反馈分别验证。 */
public class NacosSyncMain extends AbstractNacosExample {
    /** 启动参数选择一个场景；不传参数时运行第一个场景。 */
    public static void main(String[] args) throws Exception {
        String scenario = args.length == 0 ? "push-all" : args[0];
        NacosSyncMain example = new NacosSyncMain();
        switch (scenario) {
            case "push-all" -> example.fullPushRegistersInstanceAndAllSubscribedServices();
            case "pull-all" -> example.fullPullRestoresGovernanceForAllServices();
            case "push-service" -> example.servicePushPreservesRuntimeGovernanceAndUnrelatedRegistrations();
            case "pull-service" -> example.servicePullChangesOnlyTheRequestedService();
            case "pull-missing-service" -> example.pullingUnknownServiceReportsItsOriginalFailure();
            default -> throw new IllegalArgumentException("未知场景：" + scenario);
        }
        System.out.println("场景验证通过：" + scenario);
    }

    public void fullPushRegistersInstanceAndAllSubscribedServices() throws Exception {
        // 当前场景独占应用和注册分组，不复用其他场景的同步结果。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (StorageAccess observer = registry.openStorage(); //
             MetContext context = consumer.buildContext()) {
            // 启动本场景的应用，再登记服务。
            context.start();
            var first = context.publisher() //
                    .rsfService(EchoService.class) //
                    .name("first").register();
            var other = context.publisher() //
                    .rsfService(EchoService.class) //
                    .name("other").register();
            String firstId = first.getBindID();
            String otherId = other.getBindID();
            RegistryClient client = context.getExtension(RegistryClient.class);

            // 全量推送结束后，存储可见实例、两个服务目录和两条订阅关系。
            client.pushSync().get(20, TimeUnit.SECONDS);
            String instance = context.getInstanceID();
            await("Full registration was not visible", () -> {
                boolean runtimeReady = observer.getInstance(instance).getRuntime() != null;
                boolean firstReady = observer.getMember(firstId, MetServiceType.Consumer, instance) != null;
                boolean otherReady = observer.getMember(otherId, MetServiceType.Consumer, instance) != null;
                return runtimeReady && firstReady && otherReady;
            });
            assertNotNull(observer.getService(firstId));
            assertNotNull(observer.getService(otherId));
        }
    }

    public void fullPullRestoresGovernanceForAllServices() throws Exception {
        // 当前场景独占应用和注册分组，不复用其他场景的同步结果。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext context = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            context.start();
            var first = context.publisher() //
                    .rsfService(EchoService.class) //
                    .name("first").register();
            var other = context.publisher() //
                    .rsfService(EchoService.class) //
                    .name("other").register();
            String firstId = first.getBindID();
            String otherId = other.getBindID();
            RegistryClient client = context.getExtension(RegistryClient.class);

            // 等待注册中心基线自动生效，再进入当前推拉场景。
            admin.getServiceAdmin(firstId).putMeta("marker", "managed");
            admin.getServiceAdmin(otherId).putMeta("marker", "managed");
            await("Initial governance was not applied", () -> {
                boolean firstReady = ObjectUtils.equals("managed", first.getMetaData("marker"));
                boolean otherReady = ObjectUtils.equals("managed", other.getMetaData("marker"));
                return firstReady && otherReady;
            });
            client.pullSync().get(20, TimeUnit.SECONDS);

            // 本地修改两个服务后，全量拉取应同时恢复注册中心的值。
            first.setMetaData("marker", "local");
            other.setMetaData("marker", "local");
            client.pullSync().get(20, TimeUnit.SECONDS);
            assertEquals("managed", first.getMetaData("marker"));
            assertEquals("managed", other.getMetaData("marker"));
        }
    }

    public void servicePushPreservesRuntimeGovernanceAndUnrelatedRegistrations() throws Exception {
        // 当前场景独占应用和注册分组，不复用其他场景的同步结果。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             StorageAccess observer = registry.openStorage(); //
             MetContext context = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            context.start();
            var first = context.publisher() //
                    .rsfService(EchoService.class) //
                    .name("first").register();
            var other = context.publisher() //
                    .rsfService(EchoService.class) //
                    .name("other").register();
            String firstId = first.getBindID();
            String otherId = other.getBindID();
            RegistryClient client = context.getExtension(RegistryClient.class);

            // 等待注册中心基线自动生效，再进入当前推拉场景。
            admin.getServiceAdmin(firstId).putMeta("marker", "managed");
            admin.getServiceAdmin(otherId).putMeta("marker", "managed");
            await("Initial governance was not applied", () -> {
                boolean firstReady = ObjectUtils.equals("managed", first.getMetaData("marker"));
                boolean otherReady = ObjectUtils.equals("managed", other.getMetaData("marker"));
                return firstReady && otherReady;
            });
            client.pullSync().get(20, TimeUnit.SECONDS);

            // 记录当前实例和无关服务，验证局部同步不会改写它们。
            client.pushSync().get(20, TimeUnit.SECONDS);
            String instance = context.getInstanceID();
            await("Initial registration was not visible", () -> {
                boolean runtimeReady = observer.getInstance(instance).getRuntime() != null;
                boolean otherReady = observer.getMember(otherId, MetServiceType.Consumer, instance) != null;
                return runtimeReady && otherReady;
            });
            EntInstanceRuntimeData originalRuntime = observer.getInstance(instance).getRuntime();
            String instanceSnapshot = RegistryJson.write(originalRuntime);
            RefServiceRelationData otherMember = observer.getMember(otherId, MetServiceType.Consumer, instance);
            String otherSnapshot = RegistryJson.write(otherMember);

            // 单服务推送只上报登记，不重新应用治理或改写无关记录。
            first.setMetaData("marker", "local");
            other.setMetaData("marker", "local");
            context.getSettings().getRequestOptions().addOption("marker", "local");

            client.pushSync(firstId).get(20, TimeUnit.SECONDS);
            assertEquals("local", first.getMetaData("marker"));
            assertEquals("local", other.getMetaData("marker"));
            String instanceOption = context.getSettings().getRequestOptions().getOption("marker");
            assertEquals("local", instanceOption);
            EntInstanceRuntimeData remainingRuntime = observer.getInstance(instance).getRuntime();
            String actualInstance = RegistryJson.write(remainingRuntime);
            assertEquals(instanceSnapshot, actualInstance);
            RefServiceRelationData remaining = observer.getMember(otherId, MetServiceType.Consumer, instance);
            String actualOther = RegistryJson.write(remaining);
            assertEquals(otherSnapshot, actualOther);
        }
    }

    public void servicePullChangesOnlyTheRequestedService() throws Exception {
        // 当前场景独占应用和注册分组，不复用其他场景的同步结果。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             StorageAccess observer = registry.openStorage(); //
             MetContext context = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            context.start();
            var first = context.publisher() //
                    .rsfService(EchoService.class) //
                    .name("first").register();
            var other = context.publisher() //
                    .rsfService(EchoService.class) //
                    .name("other").register();
            String firstId = first.getBindID();
            String otherId = other.getBindID();
            RegistryClient client = context.getExtension(RegistryClient.class);

            // 等待注册中心基线自动生效，再进入当前推拉场景。
            admin.getServiceAdmin(firstId).putMeta("marker", "managed");
            admin.getServiceAdmin(otherId).putMeta("marker", "managed");
            await("Initial governance was not applied", () -> {
                boolean firstReady = ObjectUtils.equals("managed", first.getMetaData("marker"));
                boolean otherReady = ObjectUtils.equals("managed", other.getMetaData("marker"));
                return firstReady && otherReady;
            });
            client.pullSync().get(20, TimeUnit.SECONDS);

            // 记录当前实例和无关服务，验证局部同步不会改写它们。
            client.pushSync().get(20, TimeUnit.SECONDS);
            String instance = context.getInstanceID();
            await("Initial registration was not visible", () -> {
                boolean runtimeReady = observer.getInstance(instance).getRuntime() != null;
                boolean otherReady = observer.getMember(otherId, MetServiceType.Consumer, instance) != null;
                return runtimeReady && otherReady;
            });
            EntInstanceRuntimeData originalRuntime = observer.getInstance(instance).getRuntime();
            String instanceSnapshot = RegistryJson.write(originalRuntime);
            RefServiceRelationData otherMember = observer.getMember(otherId, MetServiceType.Consumer, instance);
            String otherSnapshot = RegistryJson.write(otherMember);

            // 单服务拉取只恢复目标服务，实例设置和其他服务均保持本地值。
            first.setMetaData("marker", "local");
            other.setMetaData("marker", "local");
            context.getSettings().getRequestOptions().addOption("marker", "local");

            client.pullSync(firstId).get(20, TimeUnit.SECONDS);
            assertEquals("managed", first.getMetaData("marker"));
            assertEquals("local", other.getMetaData("marker"));
            String instanceOption = context.getSettings().getRequestOptions().getOption("marker");
            assertEquals("local", instanceOption);
            EntInstanceRuntimeData remainingRuntime = observer.getInstance(instance).getRuntime();
            String actualInstance = RegistryJson.write(remainingRuntime);
            assertEquals(instanceSnapshot, actualInstance);
            RefServiceRelationData remaining = observer.getMember(otherId, MetServiceType.Consumer, instance);
            String actualOther = RegistryJson.write(remaining);
            assertEquals(otherSnapshot, actualOther);
        }
    }

    public void pullingUnknownServiceReportsItsOriginalFailure() throws Exception {
        // 当前场景独占应用和注册分组，不复用其他场景的同步结果。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext context = consumer.buildContext()) {
            // 仅启动应用，故意不登记即将拉取的服务。
            context.start();
            RegistryClient client = context.getExtension(RegistryClient.class);

            // 未登记的服务通过 Future 反馈错误，不掩盖成超时或成功。
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                client.pullSync("missing").get(20, TimeUnit.SECONDS);
            });
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
        }
    }

    private MetConfiguration createConsumerConfiguration(NacosRegistryConfiguration registry) {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setApplicationName("nacos-example-client");
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf");
        return configuration;
    }
}
