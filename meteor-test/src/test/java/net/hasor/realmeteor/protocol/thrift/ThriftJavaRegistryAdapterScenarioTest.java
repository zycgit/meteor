/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.realmeteor.environment.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Apache Java SDK 的显式注册适配；固定使用标准 Binary 编码和 Framed TCP。 */
@RunWith(Parameterized.class)
public class ThriftJavaRegistryAdapterScenarioTest {
    @Parameterized.Parameters(name = "thrift/java/adapter/{0}/binary-framed/plain")
    public static Collection<Object[]> backends() {
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { "jdbc" });
        result.add(new Object[] { "zookeeper" });
        result.add(new Object[] { "nacos" });
        return result;
    }

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;

    public ThriftJavaRegistryAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Test(timeout = 180000)
    public void officialJavaConsumerResolvesMeteorProviderThroughStorageAdapter() throws Exception {
        // 复用既有官方生成的 IDL 合同，业务实现仍由 Meteor 的公开发布入口持有。
        Path directory = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, directory)) {
            RegistryConfiguration registry = environment.configuration();
            registry.setPublishHost(ContainerProcess.runnerHost());
            MetConfiguration configuration = this.configuration(registry, "thrift-java-adapter-provider");
            configuration.connector("tcp").bind(ContainerProcess.runnerHost(), 0).protocol("thrift") //
                    .option("encoding", "binary").option("framed", true).option("multiplexed", false);
            try (MetContext provider = configuration.buildContext()) {
                provider.start();
                MetBindInfo<ThriftDemoService> service = provider.publisher() //
                        .rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                        .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                        .aliasName("thrift", "ExtendedInteropService").protocol("thrift").register();

                // 官方 Client 的 host 和 port 仅取自存储订阅结果，不读取 Meteor 监听对象。
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, service.getBindID(), //
                             "thrift")) {
                    URI endpoint = resolver.awaitCount(1).get(0);
                    assertEquals("thrift", endpoint.getScheme());
                    String expected = "注册发现 Java Thrift 中文 🙂 <&>";
                    try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", //
                            "--transport=tcp", "--protocol=binary", "--framed=true", "--multiplexed=false", //
                            "--service-name=ExtendedInteropService", "--host=" + endpoint.getHost(), //
                            "--port=" + endpoint.getPort(), "--scenario=echo", "--value=" + expected)) {
                        // Apache 官方 Client 在独立 JVM 内完成编码、网络调用和响应读取。
                        peer.requireResult(expected);
                    }
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversOfficialJavaProviderThroughStorageAdapter() throws Exception {
        // Meteor 仅声明出站通道；订阅中不指定业务目标，也不开放监听。
        Path directory = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, directory)) {
            RegistryConfiguration registry = environment.configuration();
            registry.setPublishHost(ContainerProcess.runnerHost());
            MetConfiguration configuration = this.configuration(registry, "thrift-java-adapter-consumer");
            configuration.connector("tcp").protocol("thrift") //
                    .option("encoding", "binary").option("framed", true).option("multiplexed", false);
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<ThriftDemoService> service = consumer.publisher().rsfService(ThriftDemoService.class) //
                        .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                        .aliasName("thrift", "ExtendedInteropService").protocol("thrift").register();
                assertNull(consumer.getListenAddress("thrift"));
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 官方 Processor 先真正监听；独立登记会话只上报进程和端点，不转发业务调用。
                try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", //
                        "--transport=tcp", "--protocol=binary", "--framed=true", "--multiplexed=false", //
                        "--service-name=ExtendedInteropService", "--port=0"); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    int port = peer.awaitPort();
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress("thrift", peer.host(), port, unit);
                    registrar.register(service, peer.processId(), "java", "thrift", "tcp", address, "/");
                    RegistryDiscoveryExample.awaitDiscovery(consumer, service);
                    AddressSelectionAwaiter.await(consumer, service, "echo");

                    // 正常客户端从自动地址本选址，真实对端继续使用 Apache 官方生成的服务实现。
                    ThriftClient caller = new ThriftClient(consumer.getRsfClient());
                    ThriftDemoService remote = caller.getRemote(service);
                    String expected = "官方 Java Thrift 服务发现 中文 🙂 <&>";
                    assertEquals(expected, remote.echo(expected));
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String application) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.setApplicationName(application).addExtension(registry);
        configuration.connector("tcp").protocol("rsf");
        return configuration;
    }
}
