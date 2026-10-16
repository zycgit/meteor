/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.registry.nacos.membership;
import net.hasor.example.registry.nacos.AbstractNacosExample;
import net.hasor.example.registry.nacos.EchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;
import net.hasor.meteor.registry.admin.MetEndpointFilter;
import net.hasor.meteor.registry.admin.MetServiceMember;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import static org.junit.Assert.assertEquals;

/** 分别验证成员登记、端点筛选、上下线和退出后的关系撤回。 */
public class NacosMembershipMain extends AbstractNacosExample {
    /** 启动参数选择一个场景；不传参数时运行第一个场景。 */
    public static void main(String[] args) throws Exception {
        String scenario = args.length == 0 ? "provider" : args[0];
        NacosMembershipMain example = new NacosMembershipMain();
        switch (scenario) {
            case "provider" -> example.providerRegistrationIncludesItsIdentityAndEndpoint();
            case "consumer" -> example.consumerRegistrationIncludesItsIdentityWithoutAListeningAddress();
            case "filter" -> example.providerQueryCombinesEndpointFilters();
            case "filter-mismatch" -> example.providerQueryRejectsMismatchedProtocol();
            case "offline" -> example.takingProviderOfflineWithdrawsItsDiscoveredAddress();
            case "online" -> example.bringingProviderOnlineRestoresDiscovery();
            case "provider-close" -> example.closingProviderRemovesOnlyItsPublication();
            case "consumer-close" -> example.closingConsumerRemovesOnlyItsSubscription();
            case "catalog-retention" -> example.serviceCatalogSurvivesAllMembersClosing();
            default -> throw new IllegalArgumentException("未知场景：" + scenario);
        }
        System.out.println("场景验证通过：" + scenario);
    }

    public void providerRegistrationIncludesItsIdentityAndEndpoint() throws Exception {
        // 为当前场景创建独立分组和所需的应用配置。
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
            var service = admin.getServiceAdmin(published.getBindID());
            MetDirectoryQuery query = new MetDirectoryQuery();

            // 发布关系携带应用身份、协议端点及可访问地址。
            await("Provider was not registered", () -> {
                var providers = service.providers(null, query);
                return providers.getItems().size() == 1;
            });
            MetServiceMember member = service.providers(null, query).getItems().get(0);
            assertEquals(server.getInstanceID(), member.getInstance().getInstanceId());
            assertEquals("nacos-example-server", member.getInstance().getApplicationName());
            assertEquals("rsf", member.getEndpoints()[0].getProtocol());
            assertEquals(1, member.getAddresses().length);
        }
    }

    public void consumerRegistrationIncludesItsIdentityWithoutAListeningAddress() throws Exception {
        // 为当前场景创建独立分组和所需的应用配置。
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
            var service = admin.getServiceAdmin(subscribed.getBindID());
            MetDirectoryQuery query = new MetDirectoryQuery();

            // 消费者登记订阅身份和协议挂载，但没有监听地址。
            await("Consumer was not registered", () -> {
                var consumers = service.consumers(null, query);
                return consumers.getItems().size() == 1;
            });
            MetServiceMember member = service.consumers(null, query).getItems().get(0);
            assertEquals(client.getInstanceID(), member.getInstance().getInstanceId());
            assertEquals("nacos-example-client", member.getInstance().getApplicationName());
            assertEquals("rsf", member.getEndpoints()[0].getProtocol());
            assertEquals(0, member.getAddresses().length);
        }
    }

    public void providerQueryCombinesEndpointFilters() throws Exception {
        // 为当前场景创建独立分组和所需的应用配置。
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
            var service = admin.getServiceAdmin(published.getBindID());
            MetDirectoryQuery query = new MetDirectoryQuery();
            await("Provider was not registered", () -> {
                var providers = service.providers(null, query);
                return providers.getItems().size() == 1;
            });

            // 同时限定实例、传输和协议，只返回完整匹配的成员。
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setInstanceId(server.getInstanceID());
            filter.setTransport("tcp");
            filter.setProtocol("rsf");
            assertEquals(1, service.providers(filter, query).getItems().size());

        }
    }

    public void providerQueryRejectsMismatchedProtocol() throws Exception {
        // 为当前场景创建独立分组和所需的应用配置。
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
            var service = admin.getServiceAdmin(published.getBindID());
            MetDirectoryQuery query = new MetDirectoryQuery();
            await("Provider was not registered", () -> {
                var providers = service.providers(null, query);
                return providers.getItems().size() == 1;
            });

            // 实例和传输匹配，但协议不匹配时不能返回该成员。
            MetEndpointFilter filter = new MetEndpointFilter();
            filter.setInstanceId(server.getInstanceID());
            filter.setTransport("tcp");
            filter.setProtocol("missing");
            assertEquals(0, service.providers(filter, query).getItems().size());

        }
    }

    public void takingProviderOfflineWithdrawsItsDiscoveredAddress() throws Exception {
        // 为当前场景创建独立分组和所需的应用配置。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration provider = this.createProviderConfiguration(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            // 启动本场景的应用，再登记服务。
            server.start();
            client.start();
            EchoService implementation = value -> "server:" + value;
            var published = server.publisher() //
                    .rsfService(EchoService.class, implementation) //
                    .name("echo").register();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = published.getBindID();
            assertEquals(serviceId, subscribed.getBindID());
            await("Client did not discover provider", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });

            // 下线通过注册中心通知消费者撤回地址，不主动调用 pullSync。
            server.offline();
            await("Offline provider is still discoverable", () -> {
                return client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });
        }
    }

    public void bringingProviderOnlineRestoresDiscovery() throws Exception {
        // 为当前场景创建独立分组和所需的应用配置。
        NacosRegistryConfiguration registry = this.createRegistryConfiguration();
        MetConfiguration provider = this.createProviderConfiguration(registry);
        MetConfiguration consumer = this.createConsumerConfiguration(registry);
        try (MetContext server = provider.buildContext(); //
             MetContext client = consumer.buildContext()) {
            // 启动本场景的应用，再登记服务。
            server.start();
            client.start();
            EchoService implementation = value -> "server:" + value;
            var published = server.publisher() //
                    .rsfService(EchoService.class, implementation) //
                    .name("echo").register();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = published.getBindID();
            assertEquals(serviceId, subscribed.getBindID());
            await("Client did not discover provider", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });

            // 先准备已下线状态，再单独验证重新上线。
            server.offline();
            await("Provider did not go offline", () -> {
                return client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });

            // 重新上线后自动发现恢复，真实业务调用成功。
            server.online();
            await("Online provider was not rediscovered", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });
            EchoService remote = client.getRsfClient().getRemote(subscribed);
            assertEquals("server:back", remote.echo("back"));
        }
    }

    public void closingProviderRemovesOnlyItsPublication() throws Exception {
        // 为当前场景创建独立分组和所需的应用配置。
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
            EchoService implementation = value -> "server:" + value;
            var published = server.publisher() //
                    .rsfService(EchoService.class, implementation) //
                    .name("echo").register();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = published.getBindID();
            assertEquals(serviceId, subscribed.getBindID());
            var service = admin.getServiceAdmin(serviceId);
            MetDirectoryQuery query = new MetDirectoryQuery();
            await("Client did not discover provider", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });

            await("Consumer was not registered", () -> {
                var consumers = service.consumers(null, query);
                return consumers.getItems().size() == 1;
            });

            // 关闭提供者撤回发布关系和地址，消费者的订阅仍然保留。
            server.close();
            await("Closed provider retained its address", () -> {
                return client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });
            await("Provider relation was not removed", () -> {
                var providers = service.providers(null, query);
                return providers.getItems().size() == 0;
            });
            assertEquals(1, service.consumers(null, query).getItems().size());
        }
    }

    public void closingConsumerRemovesOnlyItsSubscription() throws Exception {
        // 为当前场景创建独立分组和所需的应用配置。
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
            EchoService implementation = value -> "server:" + value;
            var published = server.publisher() //
                    .rsfService(EchoService.class, implementation) //
                    .name("echo").register();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = published.getBindID();
            assertEquals(serviceId, subscribed.getBindID());
            var service = admin.getServiceAdmin(serviceId);
            MetDirectoryQuery query = new MetDirectoryQuery();
            await("Client did not discover provider", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });

            await("Consumer was not registered", () -> {
                var consumers = service.consumers(null, query);
                return consumers.getItems().size() == 1;
            });

            // 关闭消费者只撤回订阅，提供者保持登记。
            client.close();
            await("Consumer relation was not removed", () -> {
                var consumers = service.consumers(null, query);
                return consumers.getItems().size() == 0;
            });
            assertEquals(1, service.providers(null, query).getItems().size());
        }
    }

    public void serviceCatalogSurvivesAllMembersClosing() throws Exception {
        // 为当前场景创建独立分组和所需的应用配置。
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
            EchoService implementation = value -> "server:" + value;
            var published = server.publisher() //
                    .rsfService(EchoService.class, implementation) //
                    .name("echo").register();
            var subscribed = client.publisher() //
                    .rsfService(EchoService.class) //
                    .name("echo").register();
            String serviceId = published.getBindID();
            assertEquals(serviceId, subscribed.getBindID());
            var service = admin.getServiceAdmin(serviceId);
            MetDirectoryQuery query = new MetDirectoryQuery();
            await("Client did not discover provider", () -> {
                return !client.getUpdater().queryAvailableAddresses(serviceId).isEmpty();
            });

            await("Consumer was not registered", () -> {
                var consumers = service.consumers(null, query);
                return consumers.getItems().size() == 1;
            });

            // 所有临时成员退出后，持久服务目录仍可查询。
            server.close();
            client.close();
            await("Members were not withdrawn", () -> {
                boolean noProviders = service.providers(null, query).getItems().size() == 0;
                boolean noConsumers = service.consumers(null, query).getItems().size() == 0;
                return noProviders && noConsumers;
            });
            assertEquals(serviceId, service.info().getServiceId());
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
