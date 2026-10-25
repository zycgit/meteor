/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.example.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 原生 rpcgen 合约通过显式存储适配发现，C 进程仍直接执行业务 RPC。 */
@RunWith(Parameterized.class)
public class OncRpcRegistryAdapterScenarioTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;
    private final String transport;
    private final String protocol;

    public OncRpcRegistryAdapterScenarioTest(String backend, String transport) {
        this.backend = backend;
        this.transport = transport;
        this.protocol = StringUtils.equalsIgnoreCase("udp", transport) ? "oncrpc-udp" : "oncrpc";
    }

    @Parameterized.Parameters(name = "adapter/c/{0}/{1}")
    public static Collection<Object[]> configurations() {
        List<Object[]> result = new ArrayList<>();
        for (String backend : List.of("jdbc", "zookeeper", "nacos")) {
            for (String transport : List.of("tcp", "udp")) {
                result.add(new Object[] { backend, transport });
            }
        }
        return result;
    }

    @Test(timeout = 180000)
    public void nativeConsumerResolvesMeteorProviderThroughStorageAdapter() throws Exception {
        // 原生对端沿用 rpcgen 标准互通合约；注册信息来自正式 Meteor 发布链路。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "native-consumer-target");
            int port = this.listenPort();
            configuration.connector(this.transport).bind(ContainerProcess.runnerHost(), port).protocol(this.protocol);
            try (MetContext provider = configuration.buildContext()) {
                provider.start();
                MetBindInfo<OncRpcDemoService> service = provider.publisher() //
                        .rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                        .protocol(this.protocol).bindResource(this.protocol, OncRpcDemoCodecs.resource()).register();

                // C 消费者未取得监听对象，只有 resolver 从真实注册库读出的服务端点。
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver( //
                             storage, service.getBindID(), this.protocol)) {
                    URI target = resolver.awaitCount(1).get(0);
                    assertEquals(this.protocol, target.getScheme());
                    String value = "discovered-native-to-Meteor";
                    try (OncRpcNativeProcess peer = OncRpcNativeProcess.start( //
                            "--command=client", "--transport=" + this.transport, //
                            "--host=" + target.getHost(), "--port=" + target.getPort(), //
                            "--scenario=echo", "--value=" + value)) {
                        peer.requireResult("echo=" + value);
                    }
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversRegisteredNativeProvider() throws Exception {
        // 消费者仅配置出站协议和注册库，先订阅尚未出现的服务。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "native-provider-consumer");
            configuration.connector(this.transport).protocol(this.protocol);
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<OncRpcDemoService> service = consumer.publisher() //
                        .rsfService(OncRpcDemoService.class).protocol(this.protocol) //
                        .bindResource(this.protocol, OncRpcDemoCodecs.resource()).register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 独立 libtirpc 服务端报告监听完成之后，登记自己的实际进程和端点。
                try (OncRpcNativeProcess peer = OncRpcNativeProcess.start( //
                        "--command=server", "--transport=" + this.transport, "--port=0"); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    int port = peer.awaitPort();
                    String unitName = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress(this.protocol, peer.host(), port, unitName);
                    registrar.register(service, peer.processId(), "c", this.protocol, this.transport, address, "/");
                    AddressSelectionAwaiter.await(consumer, service, "echo");

                    // 代理使用自动地址本，业务结果只能来自原生服务端，不传固定远端地址。
                    OncRpcDemoService remote = consumer.getRsfClient().getRemote(service);
                    String value = "Meteor-to-discovered-native";
                    assertEquals(value, remote.echo(value));
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String application) throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setApplicationName(application);
        configuration.setDataHome(this.data.newFolder(application).toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 内部管理出口不对外监听。
        return configuration;
    }

    private int listenPort() throws IOException {
        if (!StringUtils.equals(this.transport, "udp")) {
            return 0;
        }
        // UDP 的公开监听入口要求具体端口，沿用原生互通场景的系统临时端口分配方式。
        try (DatagramSocket reservation = new DatagramSocket(0, InetAddress.getByName(ContainerProcess.runnerHost()))) {
            return reservation.getLocalPort();
        }
    }
}
