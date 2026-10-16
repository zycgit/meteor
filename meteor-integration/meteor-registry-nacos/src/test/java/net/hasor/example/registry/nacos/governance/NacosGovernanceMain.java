/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.registry.nacos.governance;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.example.registry.nacos.AbstractNacosExample;
import net.hasor.example.registry.nacos.EchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import static org.junit.Assert.*;

/** 每个方法独立验证一种存储治理操作经 Nacos 订阅作用于应用的结果。 */
public class NacosGovernanceMain extends AbstractNacosExample {
    /** 启动参数选择一个场景；不传参数时运行第一个场景。 */
    public static void main(String[] args) throws Exception {
        String scenario = args.length == 0 ? "global-meta" : args[0];
        NacosGovernanceMain example = new NacosGovernanceMain();
        switch (scenario) {
            case "global-meta" -> example.globalMetadataAppliesToAllServices();
            case "service-meta" -> example.serviceMetadataOverridesGlobalValuesOnlyForThatService();
            case "instance-meta" -> example.instanceServiceMetadataOverridesOnlyTheSelectedInstance();
            case "remove-instance-meta" -> example.removingInstanceMetadataRestoresServiceInheritance();
            case "remove-service-meta" -> example.removingServiceMetadataRestoresGlobalInheritance();
            case "route-update" -> example.updatingRouteAppliesItsStoredContent();
            case "route-disable" -> example.disablingRouteClearsRuntimeButKeepsStoredContent();
            case "route-enable" -> example.enablingRouteRestoresItsStoredContent();
            case "route-reset" -> example.resettingRouteRemovesTheAppliedRule();
            case "instance-rule-disable" -> example.instanceSwitchDisablesInheritedRule();
            case "cross-rule-disable" -> example.membershipSwitchOnlyDisablesTheSelectedService();
            case "service-disable" -> example.disablingServiceLeavesOtherServicesCallable();
            case "service-enable" -> example.enablingServiceRestoresDiscoveryAndInvocation();
            case "instance-block" -> example.blockingInstanceRejectsBusinessCallsWhileManagementRemainsAvailable();
            case "instance-unblock" -> example.unblockingInstanceRestoresBusinessCalls();
            default -> throw new IllegalArgumentException("未知场景：" + scenario);
        }
        System.out.println("场景验证通过：" + scenario);
    }

    public void globalMetadataAppliesToAllServices() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration provider = this.createProviderConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext server = provider.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            server.start();
            var echo = server.publisher() //
                    .rsfService(EchoService.class, value -> value) //
                    .name("echo").register();
            var other = server.publisher() //
                    .rsfService(EchoService.class, value -> value) //
                    .name("other").register();

            // 全局元数据自动下发到两个不同服务。
            admin.getClusterAdmin().putMeta("owner", "global");
            await("Global metadata was not applied to both services", () -> {
                boolean echoReady = ObjectUtils.equals("global", echo.getMetaData("owner"));
                boolean otherReady = ObjectUtils.equals("global", other.getMetaData("owner"));
                return echoReady && otherReady;
            });
        }
    }

    public void serviceMetadataOverridesGlobalValuesOnlyForThatService() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration provider = this.createProviderConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext server = provider.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            server.start();
            var echo = server.publisher() //
                    .rsfService(EchoService.class, value -> value) //
                    .name("echo").register();
            var other = server.publisher() //
                    .rsfService(EchoService.class, value -> value) //
                    .name("other").register();
            // 先建立两个服务共同继承的全局基线。
            admin.getClusterAdmin().putMeta("owner", "global");
            await("Global baseline was not applied", () -> {
                boolean echoReady = ObjectUtils.equals("global", echo.getMetaData("owner"));
                boolean otherReady = ObjectUtils.equals("global", other.getMetaData("owner"));
                return echoReady && otherReady;
            });

            // 批量更新一个服务的元数据，另一个服务继续继承全局值。
            var service = admin.getServiceAdmin(echo.getBindID());
            service.putMeta(Map.of("owner", "service", "version", 2));
            await("Service metadata was not applied", () -> {
                boolean ownerReady = ObjectUtils.equals("service", echo.getMetaData("owner"));
                boolean versionReady = ObjectUtils.equals(2, echo.getMetaData("version"));
                return ownerReady && versionReady;
            });
            assertEquals("global", other.getMetaData("owner"));
            assertNull(other.getMetaData("version"));
        }
    }

    public void instanceServiceMetadataOverridesOnlyTheSelectedInstance() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration provider = this.createProviderConfiguration(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            server.start();
            client.start();
            var published = server.publisher() //
                    .rsfService(EchoService.class, value -> value) //
                    .name("echo").register();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = published.getBindID();
            admin.getServiceAdmin(serviceId).putMeta("owner", "service");
            await("Service baseline was not applied", () -> {
                boolean providerReady = ObjectUtils.equals("service", published.getMetaData("owner"));
                boolean consumerReady = ObjectUtils.equals("service", subscribed.getMetaData("owner"));
                return providerReady && consumerReady;
            });

            // 实例内服务配置只覆盖该实例，其余实例仍使用服务级配置。
            var local = admin.getServiceAdmin(serviceId, server.getInstanceID());
            await("Provider registration was not visible", () -> !local.providers(null, null).getItems().isEmpty());
            local.putMeta("owner", "instance");
            await("Instance metadata was not applied", () -> {
                return ObjectUtils.equals("instance", published.getMetaData("owner"));
            });
            assertEquals("service", subscribed.getMetaData("owner"));
        }
    }

    public void removingInstanceMetadataRestoresServiceInheritance() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration provider = this.createProviderConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext server = provider.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            server.start();
            var published = server.publisher() //
                    .rsfService(EchoService.class, value -> value) //
                    .name("echo").register();
            String serviceId = published.getBindID();
            admin.getServiceAdmin(serviceId).putMeta("owner", "service");
            var local = admin.getServiceAdmin(serviceId, server.getInstanceID());
            await("Provider registration was not visible", () -> !local.providers(null, null).getItems().isEmpty());
            local.putMeta("owner", "instance");
            await("Instance override was not applied", () -> {
                return ObjectUtils.equals("instance", published.getMetaData("owner"));
            });

            // 删除实例内覆盖后重新继承服务级元数据。
            local.removeMeta("owner");
            await("Service metadata was not inherited", () -> {
                return ObjectUtils.equals("service", published.getMetaData("owner"));
            });
        }
    }

    public void removingServiceMetadataRestoresGlobalInheritance() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration provider = this.createProviderConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext server = provider.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            server.start();
            var published = server.publisher() //
                    .rsfService(EchoService.class, value -> value) //
                    .name("echo").register();
            admin.getClusterAdmin().putMeta("owner", "global");
            var service = admin.getServiceAdmin(published.getBindID());
            service.putMeta(Map.of("owner", "service", "version", 2));
            await("Service override was not applied", () -> {
                boolean ownerReady = ObjectUtils.equals("service", published.getMetaData("owner"));
                boolean versionReady = ObjectUtils.equals(2, published.getMetaData("version"));
                return ownerReady && versionReady;
            });

            // 批量删除覆盖键：owner 恢复继承，version 没有继承值则被移除。
            service.removeMeta("owner", "version");
            await("Global metadata was not inherited", () -> {
                boolean ownerReady = ObjectUtils.equals("global", published.getMetaData("owner"));
                boolean versionRemoved = published.getMetaData("version") == null;
                return ownerReady && versionRemoved;
            });
        }
    }

    public void updatingRouteAppliesItsStoredContent() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext client = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            client.start();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = subscribed.getBindID();
            var service = admin.getServiceAdmin(serviceId);
            String rule = "<route><return value='addresses'/></route>";

            // 更新的规则通过自动订阅进入应用。
            service.updateRule(MetRuleType.SERVICE_ROUTE, rule);
            await("Service route was not applied", () -> {
                String applied = client.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(rule, applied);
            });

            assertEquals(rule, service.fetchRule(MetRuleType.SERVICE_ROUTE));
        }
    }

    public void disablingRouteClearsRuntimeButKeepsStoredContent() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext client = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            client.start();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = subscribed.getBindID();
            var service = admin.getServiceAdmin(serviceId);
            String rule = "<route><return value='addresses'/></route>";

            // 先保存并等待规则生效，作为本次状态变更的起点。
            service.updateRule(MetRuleType.SERVICE_ROUTE, rule);
            await("Service route was not applied", () -> {
                String applied = client.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(rule, applied);
            });

            // 禁用只清空应用当前规则，注册中心保留正文。
            service.disableRule(MetRuleType.SERVICE_ROUTE);
            await("Route was not disabled", () -> {
                String applied = client.getUpdater().serviceRoute(serviceId);
                return StringUtils.isBlank(applied);
            });

            assertFalse(service.isRule(MetRuleType.SERVICE_ROUTE));
            assertEquals(rule, service.fetchRule(MetRuleType.SERVICE_ROUTE));
        }
    }

    public void enablingRouteRestoresItsStoredContent() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext client = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            client.start();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = subscribed.getBindID();
            var service = admin.getServiceAdmin(serviceId);
            String rule = "<route><return value='addresses'/></route>";

            // 先保存并等待规则生效，作为本次状态变更的起点。
            service.updateRule(MetRuleType.SERVICE_ROUTE, rule);
            await("Service route was not applied", () -> {
                String applied = client.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(rule, applied);
            });

            service.disableRule(MetRuleType.SERVICE_ROUTE);
            await("Route was not disabled", () -> {
                String applied = client.getUpdater().serviceRoute(serviceId);
                return StringUtils.isBlank(applied);
            });

            // 从已禁用状态重新启用，无需再次提交正文。
            service.enableRule(MetRuleType.SERVICE_ROUTE);
            await("Route was not restored", () -> {
                String applied = client.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(rule, applied);
            });
            assertTrue(service.isRule(MetRuleType.SERVICE_ROUTE));
        }
    }

    public void instanceSwitchDisablesInheritedRule() throws Exception {
        // 从公开 bootstrap 接入真实 Nacos，服务继承全局路由。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext client = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);
            client.start();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class).name("echo").register();
            String serviceId = subscribed.getBindID();
            String rule = "<route><return value='addresses'/></route>";
            admin.getClusterAdmin().updateRule(MetRuleType.SERVICE_ROUTE, rule);
            await("Global route was not inherited", () -> {
                return StringUtils.equals(rule, client.getUpdater().serviceRoute(serviceId));
            });

            // 实例详情只保存禁用开关，无需复制上层 XML，订阅应清除应用规则。
            var instance = admin.getInstanceAdmin(client.getInstanceID());
            instance.disableRule(MetRuleType.SERVICE_ROUTE);
            await("Instance rule switch was not applied", () -> {
                return StringUtils.isBlank(client.getUpdater().serviceRoute(serviceId));
            });
            assertFalse(instance.isRuleEnabled(MetRuleType.SERVICE_ROUTE));
            assertNull(instance.fetchRule(MetRuleType.SERVICE_ROUTE));
            assertEquals(rule, admin.getClusterAdmin().fetchRule(MetRuleType.SERVICE_ROUTE));
        }
    }

    public void membershipSwitchOnlyDisablesTheSelectedService() throws Exception {
        // 一个实例订阅两项业务服务，均继承全局规则。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext client = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);
            client.start();
            var echo = client.publisher() //
                    .rsfService(EchoService.class).name("echo").register();
            var other = client.publisher() //
                    .rsfService(EchoService.class).name("other").register();
            String rule = "<route><return value='addresses'/></route>";
            admin.getClusterAdmin().updateRule(MetRuleType.SERVICE_ROUTE, rule);
            await("Global route was not inherited", () -> {
                boolean echoReady = StringUtils.equals(rule, client.getUpdater().serviceRoute(echo.getBindID()));
                boolean otherReady = StringUtils.equals(rule, client.getUpdater().serviceRoute(other.getBindID()));
                return echoReady && otherReady;
            });

            // 关系详情中的开关只作用于选定实例服务，另一项服务继续继承。
            var selected = admin.getServiceAdmin(echo.getBindID(), client.getInstanceID());
            selected.disableRule(MetRuleType.SERVICE_ROUTE);
            await("Membership rule switch was not applied", () -> {
                return StringUtils.isBlank(client.getUpdater().serviceRoute(echo.getBindID()));
            });
            assertEquals(rule, client.getUpdater().serviceRoute(other.getBindID()));
            assertFalse(selected.isRule(MetRuleType.SERVICE_ROUTE));
            assertNull(selected.fetchRule(MetRuleType.SERVICE_ROUTE));
        }
    }

    public void resettingRouteRemovesTheAppliedRule() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext client = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            client.start();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = subscribed.getBindID();
            var service = admin.getServiceAdmin(serviceId);
            String rule = "<route><return value='addresses'/></route>";

            // 先保存并等待规则生效，作为本次状态变更的起点。
            service.updateRule(MetRuleType.SERVICE_ROUTE, rule);
            await("Service route was not applied", () -> {
                String applied = client.getUpdater().serviceRoute(serviceId);
                return StringUtils.equals(rule, applied);
            });

            // 重置同时移除存储正文和应用中的规则。
            service.resetRule(MetRuleType.SERVICE_ROUTE);
            await("Route was not reset", () -> {
                String applied = client.getUpdater().serviceRoute(serviceId);
                return StringUtils.isBlank(applied);
            });
            String storedRule = service.fetchRule(MetRuleType.SERVICE_ROUTE);
            assertTrue(StringUtils.isBlank(storedRule));
        }
    }

    public void disablingServiceLeavesOtherServicesCallable() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration provider = this.createProviderConfiguration(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            server.start();
            client.start();
            EchoService implementation = value -> "echo:" + value;
            var published = server.publisher() //
                    .rsfService(EchoService.class, implementation) //
                    .name("echo").register();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = published.getBindID();
            var service = admin.getServiceAdmin(serviceId);
            await("Provider was not discovered", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });

            // 另一个服务作为范围隔离对照，调用必须不受禁用影响。
            EchoService otherService = value -> "other:" + value;
            var other = server.publisher() //
                    .rsfService(EchoService.class, otherService) //
                    .name("other").register();
            var otherClient = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("other").register();
            String otherId = other.getBindID();
            await("Other provider was not discovered", () -> {
                return !client.getUpdater().queryAvailableAddresses(otherId).isEmpty();
            });

            // 禁用仅撤回目标服务的地址，其他服务仍可调用。
            service.disable();
            await("Disabled service retained its address", () -> {
                return client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });
            assertFalse(service.isEnable());
            EchoService remote = client.getRsfClient().getRemote(otherClient);
            assertEquals("other:ok", remote.echo("ok"));
        }
    }

    public void enablingServiceRestoresDiscoveryAndInvocation() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration provider = this.createProviderConfiguration(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            server.start();
            client.start();
            EchoService implementation = value -> "echo:" + value;
            var published = server.publisher() //
                    .rsfService(EchoService.class, implementation) //
                    .name("echo").register();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = published.getBindID();
            var service = admin.getServiceAdmin(serviceId);
            await("Provider was not discovered", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });

            service.disable();
            await("Service was not disabled", () -> {
                return client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });

            // 单独验证已禁用服务重新启用后的发现和调用。
            service.enable();
            await("Enabled service was not rediscovered", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });
            EchoService remote = client.getRsfClient().getRemote(subscribed);
            assertEquals("echo:ok", remote.echo("ok"));
        }
    }

    public void blockingInstanceRejectsBusinessCallsWhileManagementRemainsAvailable() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration provider = this.createProviderConfiguration(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            server.start();
            client.start();
            EchoService implementation = value -> "echo:" + value;
            var published = server.publisher() //
                    .rsfService(EchoService.class, implementation) //
                    .name("echo").register();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = published.getBindID();
            await("Provider was not discovered", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });
            var instance = admin.getInstanceAdmin(server.getInstanceID());
            EchoService remote = client.getRsfClient().getRemote(subscribed);

            // 阻断网络业务调用，管理端仍可通过注册中心访问实例。
            instance.block();
            await("Block was not applied", () -> instance.instance().isBlock());
            UndeclaredThrowableException failure = assertThrows(UndeclaredThrowableException.class, () -> {
                remote.echo("blocked");
            });

            // Java 接口未声明异常，代理保留 Future 的异常链；核对实际业务拒绝原因。
            Throwable invocationFailure = failure.getCause();
            assertTrue(invocationFailure instanceof ExecutionException);
            Throwable rejectionCause = invocationFailure.getCause();
            assertTrue(rejectionCause instanceof MetException);
            MetException rejected = (MetException) rejectionCause;
            assertEquals(403, rejected.getStatus());
            boolean registryReachable = admin.getClusterAdmin().status().isReachable();
            assertTrue(registryReachable);
        }
    }

    public void unblockingInstanceRestoresBusinessCalls() throws Exception {
        // 只为当前治理行为准备独立的注册分组和应用。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration administration = new MetConfiguration().addExtension(registry);
        MetConfiguration provider = this.createProviderConfiguration(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext administrationContext = administration.buildContext(); //
             MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            RegistryAdmin admin = administrationContext.getExtension(RegistryAdmin.class);

            // 启动本场景的应用，再登记服务。
            server.start();
            client.start();
            EchoService implementation = value -> "echo:" + value;
            var published = server.publisher() //
                    .rsfService(EchoService.class, implementation) //
                    .name("echo").register();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = published.getBindID();
            await("Provider was not discovered", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });
            var instance = admin.getInstanceAdmin(server.getInstanceID());
            EchoService remote = client.getRsfClient().getRemote(subscribed);

            // 先等待阻断生效，随后只验证解除动作。
            instance.block();
            await("Initial block was not applied", () -> instance.instance().isBlock());

            // 从已阻断状态解除，业务接口恢复调用。
            instance.unblock();
            await("Block was not cleared", () -> !instance.instance().isBlock());

            // 服务端状态与消费端地址订阅分别收敛，调用前也要确认地址已恢复。
            await("Provider address was not restored", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });
            assertEquals("echo:restored", remote.echo("restored"));
        }
    }

    private MetConfiguration createProviderConfiguration(NacosRegistryConfiguration registry) {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setApplicationName("nacos-example-server");
        configuration.addExtension(registry);
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        return configuration;
    }

    private MetConfiguration createConsumerConfiguration(NacosRegistryConfiguration registry) {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setApplicationName("nacos-example-client");
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf");
        return configuration;
    }
}
