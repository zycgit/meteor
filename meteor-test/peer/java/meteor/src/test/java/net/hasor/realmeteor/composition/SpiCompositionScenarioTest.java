/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.composition;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import net.hasor.example.composition.CompositionEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.ConnectorSpiTest;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.transport.TransportFactory;
import net.hasor.realmeteor.protocol.hprose.bootstrap.spi.TestHproseProtocolA;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 所有插件同时进入真实运行类路径后，SPI 和序列化器保持独立。 */
@NxnRpc(name = "rsf", version = "1")
@NxnRpc(name = "grpc")
@NxnRpc(name = "grpc-web")
@NxnRpc(name = "hprose")
@NxnRpc(name = "soap")
@NxnRpc(name = "jsonrpc")
@NxnRpc(name = "xmlrpc")
@NxnRpc(name = "hessian")
@NxnRpc(name = "connect")
@NxnRpc(name = "triple")
@NxnRpc(name = "dubbo")
@NxnRpc(name = "thrift")
@NxnRpc(name = "oncrpc")
public class SpiCompositionScenarioTest {
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void allProtocolAndTransportProvidersCoexistInOneBootstrapRuntime() throws Exception {
        // 测试宿主登记的扩展同时检查实现类型与 SPI 名称，不排除未知提供者。
        Map<Class<?>, String> expectedTestProtocols = Map.of( //
                ConnectorSpiTest.CustomProtocol.class, "custom-test", //
                ConnectorSpiTest.FailingProtocol.class, "failing-test", //
                ConnectorSpiTest.SecondFailingProtocol.class, "failing-second", //
                TestHproseProtocolA.class, "hprosea");
        Map<Class<?>, String> testProtocols = new HashMap<>();

        // 从真实应用类加载器发现所有入口，跨生产与测试提供者的重名同样失败。
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Set<String> protocolNames = new HashSet<>();
        Set<String> protocols = new HashSet<>();
        for (ProtocolProvider<?> provider : ServiceLoader.load(ProtocolProvider.class, loader)) {
            String name = provider.name().toLowerCase(Locale.ROOT);
            assertTrue(protocolNames.add(name), "Duplicate protocol SPI: " + name);
            if (expectedTestProtocols.containsKey(provider.getClass())) {
                String previous = testProtocols.put(provider.getClass(), name);
                assertNull(previous, "Duplicate test protocol provider: " + provider.getClass().getName());
            } else {
                protocols.add(name);
            }
        }
        Set<String> expected = Set.of("rsf", "hprose", "soap", "jsonrpc", "xmlrpc", "hessian", //
                "grpc", "grpc-web", "connect", "triple", "dubbo", "thrift", "thrift-http", "oncrpc", "oncrpc-udp");
        assertEquals(expected, protocols);
        assertEquals(expectedTestProtocols, testProtocols);

        // 传输入口同样保留生产全集与测试全集，新增或缺失提供者均需显式审查。
        Map<Class<?>, String> expectedTestTransports = Map.of( //
                ConnectorSpiTest.CustomTransport.class, "custom", //
                ConnectorSpiTest.FailingTransport.class, "failing");
        Map<Class<?>, String> testTransports = new HashMap<>();
        Set<String> transportNames = new HashSet<>();
        Set<String> transports = new HashSet<>();
        for (TransportFactory<?> factory : ServiceLoader.load(TransportFactory.class, loader)) {
            String name = factory.name();
            assertTrue(transportNames.add(name), "Duplicate transport SPI: " + name);
            if (expectedTestTransports.containsKey(factory.getClass())) {
                String previous = testTransports.put(factory.getClass(), name);
                assertNull(previous, "Duplicate test transport provider: " + factory.getClass().getName());
            } else {
                transports.add(name);
            }
        }
        assertEquals(Set.of("tcp", "udp", "http"), transports);
        assertEquals(expectedTestTransports, testTransports);

        // 所有 SPI 同时存在时，仍从公开 bootstrap 启动并执行一次网络调用。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("rsf");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("rsf");
        outgoing.connector("tcp").protocol("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(CompositionEchoService.class, value -> "spi:" + value) //
                    .protocol("rsf").register();
            MetBindInfo<CompositionEchoService> service = client.publisher() //
                    .rsfService(CompositionEchoService.class).protocol("rsf").register();

            // 编码器由同一运行时发现，没有插件初始化覆盖内核或其它插件的编码器。
            assertNotNull(server.getSerializeCoder("Java"));
            assertNotNull(server.getSerializeCoder("JSON"));
            assertNotNull(server.getSerializeCoder("Hessian"));
            assertNotNull(server.getSerializeCoder("Hprose"));
            CompositionEchoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals("spi:all-plugins", remote.echo("all-plugins"));
        }
    }

}
