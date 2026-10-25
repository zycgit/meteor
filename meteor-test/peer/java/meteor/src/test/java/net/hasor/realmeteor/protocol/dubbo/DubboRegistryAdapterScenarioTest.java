/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.realmeteor.environment.container.ContainerProcess;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 官方 Dubbo 3 的 TCP/Hessian2 显式注册适配，与原生 ZooKeeper 注册桥分别记录。 */
@RunWith(Parameterized.class)
public class DubboRegistryAdapterScenarioTest {
    private static final String SERVICE = DubboInteropService.class.getName();
    private static final String TEXT    = "Dubbo 中文 🙂";

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;

    public DubboRegistryAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Parameterized.Parameters(name = "adapter={0},profile=dubbo3-tcp-hessian2")
    public static Collection<Object[]> backends() {
        // 各注册存储独立验证同一代表 profile，不与旧版或 TLS 做无关组合。
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { "jdbc" });
        result.add(new Object[] { "nacos" });
        result.add(new Object[] { "zookeeper" });
        return result;
    }

    @Test(timeout = 180000)
    public void providerOfficialDubboClientUsesStorageAdapter() throws Exception {
        // Meteor 通过 bootstrap 接入独占存储；只启用标准 TCP/Hessian2 业务 profile。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            registry.setPublishHost(ContainerProcess.runnerHost());
            MetConfiguration incoming = this.configuration(registry, true);
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<DubboInteropService> service = provider.publisher() //
                        .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                        .name(SERVICE).protocol("dubbo").aliasName("dubbo", SERVICE) //
                        .group("interop").version("1.0.0").serialize("Hessian2").register();

                // 显式适配器读取真实存储端点，官方 SDK 本身没有配置原生注册中心。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "dubbo")) {
                    URI discovered = resolver.awaitCount(1).get(0);
                    String scheme = discovered.getScheme();
                    assertEquals("dubbo", scheme);
                    String servicePath = "/" + SERVICE;
                    String providerHost = discovered.getHost();
                    int providerPort = discovered.getPort();
                    URI target = new URI(scheme, null, providerHost, providerPort, servicePath, null, null);

                    // 既有 --url 参数接收解析结果，真实业务调用由隔离 JVM 的 ReferenceConfig 完成。
                    try (DubboProcess peer = DubboProcess.start("--command=client", "--url=" + target, //
                            "--group=interop", "--version=1.0.0", "--scenario=echo")) {
                        JsonNode outcome = peer.awaitResult();
                        assertFalse(outcome.toString(), outcome.has("error"));
                        assertEquals(TEXT, outcome.path("value").asText());
                    }
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void consumerMeteorDiscoversOfficialDubboThroughStorageAdapter() throws Exception {
        // 不安装 nativeDubbo 桥；本场景只订阅 Meteor 存储目录，没有预设业务地址。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, false);
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<DubboInteropService> service = consumer.publisher().rsfService(DubboInteropService.class) //
                        .name(SERVICE).protocol("dubbo").aliasName("dubbo", SERVICE) //
                        .group("interop").version("1.0.0").serialize("Hessian2").register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 官方 Dubbo 3 进程沿用原合同发布服务，存储适配器只登记已就绪的真实端点。
                try (DubboProcess peer = DubboProcess.start("--command=server", "--port=0", //
                        "--group=interop", "--version=1.0.0"); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    int port = peer.awaitPort();
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress("dubbo", peer.host(), port, unit);
                    registrar.register(service, peer.processId(), "java", "dubbo", "tcp", address, "/");
                    awaitAddress(consumer, service, address);
                    AddressSelectionAwaiter.await(consumer, service, "echo");

                    // 自动地址本选择官方提供者，公开客户端真实执行二进制协议的远程调用。
                    DubboInteropService remote = consumer.getRsfClient().getRemote(service);
                    assertEquals(TEXT, remote.echo(TEXT));
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, boolean listening) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo").setDefaultTimeout(10000);
        configuration.setApplicationName(listening ? "dubbo-provider-adapter" : "dubbo-consumer-adapter");
        configuration.setDataHome(this.data.newFolder("application").toPath());
        configuration.addExtension(registry);
        ConnectorSettings connector = configuration.connector("tcp");
        if (listening) {
            // 与既有官方互通测试一致，允许 SDK 规范化 loopback 后仍访问本机监听。
            connector.bind("0.0.0.0", 0);
        }
        connector.protocol("dubbo");
        connector.protocol("rsf"); // 注册扩展内部管理出口，服务身份与业务接口分离。
        return configuration;
    }

    private static void awaitAddress(MetContext consumer, MetBindInfo<?> service, InterAddress expected) throws Exception {
        // 只观察已有订阅的公开地址本，不写入目标地址，也不通过业务调用轮询就绪。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            List<InterAddress> addresses = consumer.getUpdater().queryAvailableAddresses(service.getBindID());
            if (addresses.size() == 1 && addresses.get(0).equals(expected)) {
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while waiting for official Dubbo provider");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Official Dubbo provider was not discovered: " + expected);
            }
            LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
        }
    }
}
