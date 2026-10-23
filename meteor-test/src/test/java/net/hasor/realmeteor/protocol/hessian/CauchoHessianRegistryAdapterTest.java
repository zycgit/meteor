/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.protocol.hessian.HessianInteropDemo;
import net.hasor.example.protocol.hessian.HessianInteropDemoImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
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
import static org.junit.Assert.assertTrue;

/** Caucho 官方 JVM 经显式存储适配发现服务；SDK 本身不实现 Meteor 注册协议。 */
@RunWith(Parameterized.class)
public class CauchoHessianRegistryAdapterTest {
    @Parameterized.Parameters(name = "backend={0}, Hessian={1}")
    public static Collection<Object[]> configurations() {
        List<Object[]> result = new ArrayList<>();
        // 每项是独立的注册后端与线协议版本，不在单个方法内轮流执行场景。
        result.add(new Object[] { "jdbc", 1 });
        result.add(new Object[] { "jdbc", 2 });
        result.add(new Object[] { "zookeeper", 1 });
        result.add(new Object[] { "zookeeper", 2 });
        result.add(new Object[] { "nacos", 1 });
        result.add(new Object[] { "nacos", 2 });
        return result;
    }

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;
    private final int    version;

    public CauchoHessianRegistryAdapterTest(String backend, int version) {
        this.backend = backend;
        this.version = version;
    }

    @Test(timeout = 180000)
    public void officialConsumerResolvesMeteorProviderThroughStorageAdapter() throws Exception {
        // 使用原互通合同，Caucho proxy 与 Meteor 对同一公开 Java 签名独立编解码。
        Path directory = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, directory)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
            configuration.addExtension(registry).setApplicationName("meteor-hessian-adapter-provider");
            configuration.connector("tcp").protocol("rsf");
            configuration.connector("http").bind("127.0.0.1", 0) //
                    .protocol("hessian").addressScheme("http").contextPath("/hessian").option("version", this.version);
            try (MetContext provider = configuration.buildContext()) {
                provider.start();
                MetBindInfo<HessianInteropDemo> service = provider.publisher() //
                        .rsfService(HessianInteropDemo.class, new HessianInteropDemoImpl()) //
                        .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();

                // 适配器只订阅注册地址；官方 SDK 获得的 URL 由真实登记和固定合同别名组成。
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, service.getBindID(), //
                             "hessian")) {
                    List<URI> endpoints = resolver.awaitCount(1);
                    URI mount = endpoints.get(0);
                    assertEquals("http", mount.getScheme());
                    assertEquals("/hessian", mount.getPath());
                    HessianInteropDemo remote = CauchoHessianPeerProcess.client(this.version, mount + "/demo");

                    // 本地 proxy 只转交控制文件，网络请求与响应均由隔离的 Caucho JVM 完成。
                    String expected = "注册发现 Hessian 中文 🙂 <&>";
                    assertEquals(expected, remote.echo(expected));
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversOfficialProviderThroughStorageAdapter() throws Exception {
        // 消费者只配置出站协议和注册库，没有固定业务地址，也不开业务监听。
        Path directory = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, directory)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
            configuration.addExtension(registry).setApplicationName("meteor-hessian-adapter-consumer");
            configuration.connector("tcp").protocol("rsf");
            configuration.connector("http").protocol("hessian") //
                    .addressScheme("http").contextPath("/hessian").option("version", this.version);
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                MetBindInfo<HessianInteropDemo> service = consumer.publisher().rsfService(HessianInteropDemo.class) //
                        .aliasName("hessian", "demo").protocol("hessian").serialize("Hessian").register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 官方 Skeleton 进程先监听，显式 registrar 再登记实际 PID 与完整服务 URL。
                try (CauchoHessianPeerProcess provider = CauchoHessianPeerProcess.startServer(); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress("http", "127.0.0.1", provider.getPort(), unit);
                    registrar.register(service, provider.processId(), "java", "hessian", "http", address, //
                            "/hessian/demo");
                    RegistryDiscoveryExample.awaitDiscovery(consumer, service);
                    AddressSelectionAwaiter.await(consumer, service, "echo");

                    // 正式调用只通过自动地址本选址；对端 HTTP/Skeleton 完全属于官方独立 JVM。
                    HessianInteropDemo remote = consumer.getRsfClient().getRemote(service);
                    String expected = "官方服务发现 Hessian 中文 🙂 <&>";
                    assertEquals(expected, remote.echo(expected));
                }
            }
        }
    }
}
