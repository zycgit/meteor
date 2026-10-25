/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.example.protocol.triple.interop.TripleInteropWrappedService;
import net.hasor.example.protocol.triple.interop.TripleInteropWrappedServiceImpl;
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

/** 官方 Dubbo Triple wrapped profile 的显式注册适配，不等同于 classic Dubbo 原生注册桥。 */
@RunWith(Parameterized.class)
public class TripleRegistryAdapterScenarioTest {
    private static final String SERVICE = "net.hasor.peer.protocol.triple.DubboWrappedService";
    private static final String TEXT    = "Triple 中文 🙂";

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;

    public TripleRegistryAdapterScenarioTest(String backend) {
        this.backend = backend;
    }

    @Parameterized.Parameters(name = "adapter={0},profile=wrapped-hessian2")
    public static Collection<Object[]> backends() {
        // 三种注册后端分别执行相同的独立官方 SDK 适配场景。
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { "jdbc" });
        result.add(new Object[] { "nacos" });
        result.add(new Object[] { "zookeeper" });
        return result;
    }

    @Test(timeout = 180000)
    public void providerOfficialDubboClientUsesRegistryAdapter() throws Exception {
        // 注册存储独占；保留官方对端既有固定 wrapped 合同与输入语义。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            registry.setPublishHost(ContainerProcess.runnerHost());
            MetConfiguration incoming = this.configuration(registry, true);
            try (MetContext provider = incoming.buildContext()) {
                provider.start();
                MetBindInfo<TripleInteropWrappedService> service = provider.publisher() //
                        .rsfService(TripleInteropWrappedService.class, new TripleInteropWrappedServiceImpl()) //
                        .name(SERVICE).protocol("triple").aliasName("triple", SERVICE).serialize("Hessian2") //
                        .register();

                // 地址只能来自真实 StorageAccess 查询与订阅，不从提供者监听器读取端口。
                String serviceId = service.getBindID();
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, serviceId, "triple")) {
                    URI discovered = resolver.awaitCount(1).get(0);
                    assertEquals("http", discovered.getScheme());
                    assertEquals(ContainerProcess.runnerHost(), discovered.getHost());
                    String port = "--port=" + discovered.getPort();

                    // 官方 peer 使用注册存储公布的主机与端口建立跨容器 Triple 连接。
                    String host = "--host=" + discovered.getHost();
                    try (DubboTripleProcess peer = DubboTripleProcess.start("client", port, host, //
                            "--mode=wrapped", "--scenario=echo")) {
                        JsonNode outcome = peer.awaitResult();
                        assertFalse(outcome.toString(), outcome.has("error"));
                        assertEquals(TEXT, outcome.path("value").asText());
                    }
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void consumerMeteorDiscoversOfficialDubboThroughRegistryAdapter() throws Exception {
        // Meteor 只声明出站 Triple 和真实注册扩展，订阅不携带固定服务地址。
        Path home = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, home)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration outgoing = this.configuration(registry, false);
            try (MetContext consumer = outgoing.buildContext()) {
                consumer.start();
                MetBindInfo<TripleInteropWrappedService> service = consumer.publisher() //
                        .rsfService(TripleInteropWrappedService.class) //
                        .name(SERVICE).protocol("triple").aliasName("triple", SERVICE).serialize("Hessian2") //
                        .register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 隔离 JVM 使用既有官方 Dubbo SDK 类路径，实际启动后才登记进程与 Triple 端点。
                try (DubboTripleProcess peer = DubboTripleProcess.start("server", "--port=0"); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    int port = peer.awaitPort();
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress("http", peer.host(), port, unit);
                    registrar.register(service, peer.processId(), "java", "triple", "http", address, "/");
                    awaitAddress(consumer, service, address);
                    AddressSelectionAwaiter.await(consumer, service, "echo");

                    // 公共客户端从自动地址本选择官方提供者，业务调用仍使用 Triple 与 Hessian2。
                    TripleInteropWrappedService remote = consumer.getRsfClient().getRemote(service);
                    assertEquals(TEXT, remote.echo(TEXT));
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, boolean listening) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple").setDefaultTimeout(10000);
        configuration.setApplicationName(listening ? "triple-provider-adapter" : "triple-consumer-adapter");
        configuration.setDataHome(this.data.newFolder("application").toPath());
        configuration.addExtension(registry);
        configuration.connector("tcp").protocol("rsf"); // 注册扩展内部管理出口。
        ConnectorSettings connector = configuration.connector("http").option("httpVersion", 2);
        if (listening) {
            // 官方 SDK 会规范化 loopback；沿用既有互通测试监听方式，由注册 publishHost 明确公布地址。
            connector.bind("0.0.0.0", 0);
        }
        connector.protocol("triple").addressScheme("http").contextPath("/").option("streamBody", true);
        return configuration;
    }

    private static void awaitAddress(MetContext consumer, MetBindInfo<?> service, InterAddress expected) throws Exception {
        // 仅观察注册通知维护的公开地址本，不主动拉取、更改地址或发送探测 RPC。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            List<InterAddress> addresses = consumer.getUpdater().queryAvailableAddresses(service.getBindID());
            if (addresses.size() == 1 && addresses.get(0).equals(expected)) {
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while waiting for official Triple provider");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Official Triple provider was not discovered: " + expected);
            }
            LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
        }
    }
}
