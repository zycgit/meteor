/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.dubbo;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.dubbo.DubboRegistryClient;
import net.hasor.meteor.registry.dubbo.DubboRegistryConfiguration;
import net.hasor.realmeteor.protocol.dubbo.DubboProcess;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.junit.Test;

import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.application;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.assertInterfaceProvidersAbsent;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.awaitAddresses;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.pem;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.tlsClient;
import static net.hasor.realmeteor.registry.dubbo.DubboRegistryScenarioSupport.tlsServer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** 真实 ZooKeeper 加独立官方 JVM；两个方向都通过注册中心发现，不设置业务直连地址。 */
public class DubboRegistryOfficialInteropTest {
    @Test(timeout = 90000)
    public void referenceByClassUsesNativeEmptyGroupAndVersion() throws Exception {
        // 原生 Dubbo 不设置分组与版本；便利引用不能误用 Meteor 的默认服务身份。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-native-default-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (DubboProcess provider = DubboProcess.start( //
                    "--command=server", "--registry=" + registryUrl); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();

                // 仅传普通 Java 接口就可引用，地址仍由自动订阅提供。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                DubboInteropService remote = registry.reference(DubboInteropService.class);
                String nativeInterface = DubboInteropService.class.getName();
                MetBindInfo<DubboInteropService> service = client.getServiceInfo("dubbo", nativeInterface);
                awaitAddresses(client, service, 1);
                assertEquals("native-default", remote.echo("native-default"));
            }
        }
    }

    @Test(timeout = 90000)
    public void referenceByClassUsesDubbo27NativeEmptyIdentity() throws Exception {
        // 2.7 服务未设置分组和版本，便利引用使用原生空身份。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-legacy-default-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (DubboProcess provider = DubboProcess.startLegacy( //
                    "--command=server", "--registry=" + registryUrl); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();

                // 服务通过注册中心自动发现，调用过程没有补充 group 或 version。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                DubboInteropService remote = registry.reference(DubboInteropService.class);
                String nativeInterface = DubboInteropService.class.getName();
                MetBindInfo<DubboInteropService> service = client.getServiceInfo("dubbo", nativeInterface);
                awaitAddresses(client, service, 1);
                assertEquals("legacy-default", remote.echo("legacy-default"));
            }
        }
    }

    @Test(timeout = 90000)
    public void dubbo27NativeDefaultConsumerReferencesMeteor() throws Exception {
        // 原生默认引用没有分组和版本，Meteor 的本地身份与对外协议身份分别明确配置。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-native-default-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext server = provider.buildContext()) {
                MetBindInfo<DubboInteropService> service = server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();
                service.setMetaData("dubbo.registry.group", "");
                service.setMetaData("dubbo.registry.version", "");
                server.start();

                // 官方客户端只给注册中心地址，测试真正的原生默认引用兼容性。
                try (DubboProcess consumer = DubboProcess.startLegacy( //
                        "--command=client", "--registry=" + registryUrl)) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void dubbo33NativeDefaultConsumerReferencesMeteor() throws Exception {
        // 原生默认引用没有分组和版本，Meteor 的本地身份与对外协议身份分别明确配置。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-native-default-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext server = provider.buildContext()) {
                MetBindInfo<DubboInteropService> service = server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();
                service.setMetaData("dubbo.registry.group", "");
                service.setMetaData("dubbo.registry.version", "");
                server.start();

                // 官方客户端只给注册中心地址，测试真正的原生默认引用兼容性。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl)) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void meteorReferencesDubbo27Provider() throws Exception {
        // 独立原生服务向真实 ZooKeeper 发布，Meteor 只配置注册中心和出站传输。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-registry-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (DubboProcess provider = DubboProcess.startLegacy( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0"); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();

                // 公开引用入口根据服务身份订阅地址，业务调用经 Meteor Caller 发送。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("registry-Meteor", remote.echo("registry-Meteor"));
            }
        }
    }

    @Test(timeout = 90000)
    public void dubbo27ReferencesMeteorProvider() throws Exception {
        // Meteor 通过正式扩展连接注册中心，先启动监听再发布普通 Java 接口。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-registry-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();

                // 官方消费者只有注册中心地址，结果来自对 Meteor 发布地址的真实网络调用。
                try (DubboProcess consumer = DubboProcess.startLegacy( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0")) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void meteorReferencesDubbo27ProviderTls() throws Exception {
        // 独立原生服务向真实 ZooKeeper 发布，Meteor 只配置注册中心和出站传输。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer(); //
             DubboRegistryServerCertificate certificate = DubboRegistryServerCertificate.create()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-registry-consumer");
            ConnectorSettings endpoint = consumer.connector("tcp");
            tlsClient(endpoint, false);
            endpoint.option("tlsTrustStore", certificate.trustStore());
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (DubboProcess provider = DubboProcess.startLegacy( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0", //
                    "--tls=true", "--certificate=" + certificate.certificate(), //
                    "--private-key=" + certificate.privateKey()); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();

                // 公开引用入口根据服务身份订阅地址，业务调用经 Meteor Caller 发送。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("registry-Meteor", remote.echo("registry-Meteor"));
            }
        }
    }

    @Test(timeout = 90000)
    public void dubbo27ReferencesMeteorProviderTls() throws Exception {
        // Meteor 通过正式扩展连接注册中心，先启动监听再发布普通 Java 接口。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-registry-provider");
            ConnectorSettings endpoint = provider.connector("tcp").bind("0.0.0.0", 0);
            tlsServer(endpoint, false);
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();

                // 官方消费者只有注册中心地址，结果来自对 Meteor 发布地址的真实网络调用。
                try (DubboProcess consumer = DubboProcess.startLegacy( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", //
                        "--tls=true", "--certificate=" + pem("client"), //
                        "--private-key=" + pem("client-key"), //
                        "--trust-certificate=" + pem("ca"))) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void meteorReferencesDubbo27ProviderMutualTls() throws Exception {
        // 独立原生服务向真实 ZooKeeper 发布，Meteor 只配置注册中心和出站传输。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer(); //
             DubboRegistryServerCertificate certificate = DubboRegistryServerCertificate.create()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-registry-consumer");
            ConnectorSettings endpoint = consumer.connector("tcp");
            tlsClient(endpoint, true);
            endpoint.option("tlsTrustStore", certificate.trustStore());
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (DubboProcess provider = DubboProcess.startLegacy( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0", //
                    "--tls=true", "--certificate=" + certificate.certificate(), //
                    "--private-key=" + certificate.privateKey(), //
                    "--trust-certificate=" + pem("ca")); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();

                // 公开引用入口根据服务身份订阅地址，业务调用经 Meteor Caller 发送。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("registry-Meteor", remote.echo("registry-Meteor"));
            }
        }
    }

    @Test(timeout = 90000)
    public void dubbo27ReferencesMeteorProviderMutualTls() throws Exception {
        // Meteor 通过正式扩展连接注册中心，先启动监听再发布普通 Java 接口。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-registry-provider");
            ConnectorSettings endpoint = provider.connector("tcp").bind("0.0.0.0", 0);
            tlsServer(endpoint, true);
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();

                // 官方消费者只有注册中心地址，结果来自对 Meteor 发布地址的真实网络调用。
                try (DubboProcess consumer = DubboProcess.startLegacy( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", //
                        "--tls=true", "--certificate=" + pem("client"), //
                        "--private-key=" + pem("client-key"), //
                        "--trust-certificate=" + pem("ca"))) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void meteorReferencesDubbo33Provider() throws Exception {
        // 独立原生服务向真实 ZooKeeper 发布，Meteor 只配置注册中心和出站传输。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-registry-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (DubboProcess provider = DubboProcess.start( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0"); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();

                // 公开引用入口根据服务身份订阅地址，业务调用经 Meteor Caller 发送。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("registry-Meteor", remote.echo("registry-Meteor"));
            }
        }
    }

    @Test(timeout = 90000)
    public void dubbo33ReferencesMeteorProvider() throws Exception {
        // Meteor 通过正式扩展连接注册中心，先启动监听再发布普通 Java 接口。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-registry-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();

                // 官方消费者只有注册中心地址，结果来自对 Meteor 发布地址的真实网络调用。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0")) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void meteorReferencesDubbo33ProviderTls() throws Exception {
        // 独立原生服务向真实 ZooKeeper 发布，Meteor 只配置注册中心和出站传输。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer(); //
             DubboRegistryServerCertificate certificate = DubboRegistryServerCertificate.create()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-registry-consumer");
            ConnectorSettings endpoint = consumer.connector("tcp");
            tlsClient(endpoint, false);
            endpoint.option("tlsTrustStore", certificate.trustStore());
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (DubboProcess provider = DubboProcess.start( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0", //
                    "--tls=true", "--certificate=" + certificate.certificate(), //
                    "--private-key=" + certificate.privateKey()); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();

                // 公开引用入口根据服务身份订阅地址，业务调用经 Meteor Caller 发送。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("registry-Meteor", remote.echo("registry-Meteor"));
            }
        }
    }

    @Test(timeout = 90000)
    public void dubbo33ReferencesMeteorProviderTls() throws Exception {
        // Meteor 通过正式扩展连接注册中心，先启动监听再发布普通 Java 接口。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-registry-provider");
            ConnectorSettings endpoint = provider.connector("tcp").bind("0.0.0.0", 0);
            tlsServer(endpoint, false);
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();

                // 官方消费者只有注册中心地址，结果来自对 Meteor 发布地址的真实网络调用。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", //
                        "--tls=true", "--certificate=" + pem("client"), //
                        "--private-key=" + pem("client-key"), //
                        "--trust-certificate=" + pem("ca"))) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void meteorReferencesDubbo33ProviderMutualTls() throws Exception {
        // 独立原生服务向真实 ZooKeeper 发布，Meteor 只配置注册中心和出站传输。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer(); //
             DubboRegistryServerCertificate certificate = DubboRegistryServerCertificate.create()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-registry-consumer");
            ConnectorSettings endpoint = consumer.connector("tcp");
            tlsClient(endpoint, true);
            endpoint.option("tlsTrustStore", certificate.trustStore());
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (DubboProcess provider = DubboProcess.start( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0", //
                    "--tls=true", "--certificate=" + certificate.certificate(), //
                    "--private-key=" + certificate.privateKey(), //
                    "--trust-certificate=" + pem("ca")); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();

                // 公开引用入口根据服务身份订阅地址，业务调用经 Meteor Caller 发送。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("registry-Meteor", remote.echo("registry-Meteor"));
            }
        }
    }

    @Test(timeout = 90000)
    public void dubbo33ReferencesMeteorProviderMutualTls() throws Exception {
        // Meteor 通过正式扩展连接注册中心，先启动监听再发布普通 Java 接口。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-registry-provider");
            ConnectorSettings endpoint = provider.connector("tcp").bind("0.0.0.0", 0);
            tlsServer(endpoint, true);
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();

                // 官方消费者只有注册中心地址，结果来自对 Meteor 发布地址的真实网络调用。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", //
                        "--tls=true", "--certificate=" + pem("client"), //
                        "--private-key=" + pem("client-key"), //
                        "--trust-certificate=" + pem("ca"))) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void meteorReferencesDubbo33ApplicationOnlyProvider() throws Exception {
        // 官方 3.3 服务仅注册应用实例，不能依赖旧版接口级 providers 节点完成发现。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-application-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (DubboProcess provider = DubboProcess.start( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0", "--register-mode=instance"); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                String serviceName = DubboInteropService.class.getName();
                assertInterfaceProvidersAbsent(zookeeper.getConnectString(), serviceName);
                client.start();

                // 应用级发现还原出业务服务，调用方仍使用同一公开引用 API。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("application-Meteor", remote.echo("application-Meteor"));
            }
        }
    }

    @Test(timeout = 90000)
    public void dubbo33ApplicationOnlyConsumerReferencesMeteor() throws Exception {
        // Meteor 正式发布服务，扩展同时提供原生应用级服务发现所需的服务元信息。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-application-provider");
            provider.connector("tcp").bind("0.0.0.0", 0).protocol("dubbo");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();

                // 官方客户端明确选择应用级发现，不能通过直连或接口级目录绕过此链路。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", "--register-mode=instance")) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void meteorReferencesApplicationOnlyProviderTls() throws Exception {
        // 应用实例元信息必须保留安全端点，发现过程不能改用接口级目录。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer(); //
             DubboRegistryServerCertificate certificate = DubboRegistryServerCertificate.create()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            DubboRegistryConfiguration registryConfiguration = new DubboRegistryConfiguration() //
                    .setAddress(registryUrl) //
                    .setMetadataTrustCertificate(certificate.certificate());
            MetConfiguration consumer = new MetConfiguration().addExtension(registryConfiguration);
            consumer.setApplicationName("meteor-application-secure-consumer");
            ConnectorSettings endpoint = consumer.connector("tcp");
            tlsClient(endpoint, false);
            endpoint.option("tlsTrustStore", certificate.trustStore());
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (DubboProcess provider = DubboProcess.start( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0", "--register-mode=instance", //
                    "--tls=true", "--certificate=" + certificate.certificate(), //
                    "--private-key=" + certificate.privateKey()); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                String serviceName = DubboInteropService.class.getName();
                assertInterfaceProvidersAbsent(zookeeper.getConnectString(), serviceName);
                client.start();

                // 自动发现还原 TLS 属性后，业务调用经 Meteor 的安全出站挂载发送。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("application-TLS", remote.echo("application-TLS"));
            }
        }
    }

    @Test(timeout = 90000)
    public void applicationOnlyConsumerReferencesMeteorTls() throws Exception {
        // Meteor 只发布安全业务监听，应用级服务元信息也必须报告这个端点。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-application-secure-provider");
            ConnectorSettings endpoint = provider.connector("tcp").bind("0.0.0.0", 0);
            tlsServer(endpoint, false);
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();

                // 原生消费者强制应用级发现，使用发现到的 ssl-enabled 业务地址调用。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", "--register-mode=instance", //
                        "--tls=true", "--certificate=" + pem("client"), //
                        "--private-key=" + pem("client-key"), //
                        "--trust-certificate=" + pem("ca"))) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void meteorReferencesApplicationOnlyProviderMutualTls() throws Exception {
        // 应用实例元信息必须保留安全端点，发现过程不能改用接口级目录。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer(); //
             DubboRegistryServerCertificate certificate = DubboRegistryServerCertificate.create()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            DubboRegistryConfiguration registryConfiguration = new DubboRegistryConfiguration() //
                    .setAddress(registryUrl) //
                    .setMetadataTrustCertificate(certificate.certificate());
            registryConfiguration.setMetadataClientCertificate(pem("client"));
            registryConfiguration.setMetadataClientPrivateKey(pem("client-key"));
            MetConfiguration consumer = new MetConfiguration().addExtension(registryConfiguration);
            consumer.setApplicationName("meteor-application-secure-consumer");
            ConnectorSettings endpoint = consumer.connector("tcp");
            tlsClient(endpoint, true);
            endpoint.option("tlsTrustStore", certificate.trustStore());
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (DubboProcess provider = DubboProcess.start( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=default", "--version=1.0.0", "--register-mode=instance", //
                    "--tls=true", "--certificate=" + certificate.certificate(), //
                    "--private-key=" + certificate.privateKey(), //
                    "--trust-certificate=" + pem("ca")); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                String serviceName = DubboInteropService.class.getName();
                assertInterfaceProvidersAbsent(zookeeper.getConnectString(), serviceName);
                client.start();

                // 自动发现还原 TLS 属性后，业务调用经 Meteor 的安全出站挂载发送。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .register();
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("application-TLS", remote.echo("application-TLS"));
            }
        }
    }

    @Test(timeout = 90000)
    public void applicationOnlyConsumerReferencesMeteorMutualTls() throws Exception {
        // Meteor 只发布安全业务监听，应用级服务元信息也必须报告这个端点。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration provider = application(registryUrl, "meteor-application-secure-provider");
            ConnectorSettings endpoint = provider.connector("tcp").bind("0.0.0.0", 0);
            tlsServer(endpoint, true);
            endpoint.protocol("dubbo").addressScheme("dubbos");
            try (MetContext server = provider.buildContext()) {
                server.start();
                server.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .register();

                // 原生消费者强制应用级发现，使用发现到的 ssl-enabled 业务地址调用。
                try (DubboProcess consumer = DubboProcess.start( //
                        "--command=client", "--registry=" + registryUrl, //
                        "--group=default", "--version=1.0.0", "--register-mode=instance", //
                        "--tls=true", "--certificate=" + pem("client"), //
                        "--private-key=" + pem("client-key"), //
                        "--trust-certificate=" + pem("ca"))) {
                    JsonNode result = consumer.awaitResult();
                    assertFalse(result.toString(), result.has("error"));
                    assertEquals("Dubbo 中文 🙂", result.path("value").asText());
                }
            }
        }
    }

    @Test(timeout = 90000)
    public void referenceUsesExplicitGroupAndVersion() throws Exception {
        // 官方服务使用非默认身份，消费者通过公开发布器声明相同分组和版本。
        try (ZookeeperTestServer zookeeper = new ZookeeperTestServer()) {
            String registryUrl = "zookeeper://" + zookeeper.getConnectString();
            MetConfiguration consumer = application(registryUrl, "meteor-identity-consumer");
            consumer.connector("tcp").protocol("dubbo");
            try (DubboProcess provider = DubboProcess.start( //
                    "--command=server", "--registry=" + registryUrl, //
                    "--group=blue", "--version=2.1"); //
                 MetContext client = consumer.buildContext()) {
                provider.awaitPort();
                client.start();
                MetBindInfo<DubboInteropService> service = client.publisher() //
                        .rsfService(DubboInteropService.class) //
                        .group("blue") //
                        .version("2.1") //
                        .register();

                // 扩展使用完整服务身份引用，业务代理不能误用上下文默认版本。
                DubboRegistryClient registry = client.getExtension(DubboRegistryClient.class);
                DubboInteropService remote = registry.reference(service);
                awaitAddresses(client, service, 1);
                assertEquals("blue-2.1", remote.echo("blue-2.1"));
            }
        }
    }
}
