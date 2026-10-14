/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.protocol;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.protocol.EndpointConnectorFactory;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolFactory;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.protocol.TestEndpointConnector;
import net.hasor.meteor.support.connector.protocol.EndpointTestProtocols.HttpA;
import net.hasor.meteor.support.connector.protocol.RawSocketTestProtocol;
import net.hasor.meteor.support.connector.protocol.TextProtocol;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.NetworkConnector;
import net.hasor.meteor.connector.transport.NetworkConnectorFactory;
import net.hasor.meteor.connector.transport.tcp.TcpConnector;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 验证 SPI 发现、配置准备和连接器延迟创建契约。 */
public class ProtocolSpiTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Before
    public void reset() {
        CustomTransport.created.set(0);
        CustomTransport.prepared.set(0);
        CustomProtocol.configurations.clear();
    }

    @Test
    public void preparingCustomSpiConfigurationDoesNotAllocateTransport() throws Exception {
        // 类加载器只暴露当前用例的两个 SPI，配置中保留混合大小写名称。
        Class<?>[] transports = { CustomTransport.class };
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("CUSTOM-PROTOCOL");
        ProtocolConfig mount = config.protocol("custom");

        // 准备配置及初始化管理器不会分配网络资源，也不会复制挂载对象。
        try (ConnectorManager manager = this.manager(loader, config)) {
            manager.init();
            assertEquals(0, CustomTransport.created.get());
            assertEquals(1, CustomTransport.prepared.get());
            assertSame(mount, config.protocol("custom"));
            assertEquals("ready", mount.option("preparation", null));
            assertEquals("Java", mount.option("serializeType", null));
        }
    }

    @Test
    public void incomingAndOutgoingConnectionsReusePreparedConnector() throws Exception {
        // 通过指定类加载器组合自定义传输和协议，保存原始挂载配置。
        Class<?>[] transports = { CustomTransport.class };
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("CUSTOM-PROTOCOL");
        ProtocolConfig mount = config.protocol("custom");
        try (ConnectorManager manager = this.manager(loader, config); //
             ServerSocket server = new ServerSocket(0)) {
            // 主动连接首次创建传输，协议会话直接使用已准备的挂载配置。
            manager.init();
            InterAddress target = new InterAddress("custom", "127.0.0.1", server.getLocalPort(), "test");
            MetChannel channel = manager.connect(target).get(3, TimeUnit.SECONDS);
            assertSame(mount, CustomProtocol.configurations.poll(3, TimeUnit.SECONDS));
            server.setSoTimeout(3000);
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(3000);
                RequestPayload request = new RequestPayload();
                request.setRequestID(0);
                channel.sendData(request).get(3, TimeUnit.SECONDS);
                assertEquals(37, peer.getInputStream().read());

                // 同一个端点再开启监听时复用已经创建的连接器。
                MetListen listen = manager.bind("test").get(3, TimeUnit.SECONDS);
                assertTrue(listen.isActive());
                assertTrue(TestEndpointConnector.isEndpoint(manager.find("test")));
                assertEquals(1, CustomTransport.created.get());
                channel.close().get(3, TimeUnit.SECONDS);

                // 同一端点收到入站连接时也复用配置，不再次规范化或复制。
                try (Socket incoming = new Socket("127.0.0.1", listen.getBindAddress().getPort())) {
                    assertSame(mount, CustomProtocol.configurations.poll(3, TimeUnit.SECONDS));
                    assertEquals(1, CustomTransport.prepared.get());
                }
            }
        }
    }

    @Test
    public void protocolMissingFromContextLoaderIsRejectedWithoutAllocatingTransport() throws Exception {
        // 当前类加载器未提供请求使用的协议。
        Class<?>[] transports = { CustomTransport.class };
        ClassLoader loader = this.loader(transports, new Class<?>[0]);
        ConnectorConfig config = config("custom-protocol");

        // 准备阶段报告未知协议，不创建真实传输。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.manager(loader, config).close();
        });
        assertTrue(failure.getMessage().contains("Unknown protocol"));
        assertEquals(0, CustomTransport.created.get());
    }

    @Test
    public void incompatibleTransportIsRejectedBeforeCreatingNetworkResources() throws Exception {
        // HTTP 协议不能挂到 custom 字节传输上。
        Class<?>[] transports = { CustomTransport.class };
        Class<?>[] protocols = { HttpA.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("httpa");

        // 验证错误直接指出协议要求的传输，失败前不分配资源。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.manager(loader, config).close();
        });
        assertTrue(failure.getMessage().contains("requires transport http"));
        assertEquals(0, CustomTransport.created.get());
    }

    @Test
    public void incompatibleMessageContractIsRejectedBeforeCreatingNetworkResources() throws Exception {
        // 名称和传输匹配，但协议处理 HTTP 消息而传输提供字节数组。
        Class<?>[] transports = { CustomTransport.class };
        Class<?>[] protocols = { WrongMessageProtocol.class };
        ClassLoader loader = this.loader(transports, protocols);
        ConnectorConfig config = config("wrong-message");

        // 消息类型不匹配在准备阶段失败。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.manager(loader, config).close();
        });
        assertTrue(failure.getMessage().contains("message type"));
        assertEquals(0, CustomTransport.created.get());
    }

    @Test
    public void transportNamesMustBeUniqueIgnoringCase() throws Exception {
        // 两个传输 SPI 仅以名称大小写区别。
        Class<?>[] transports = { CustomTransport.class, DuplicateTransport.class };
        ClassLoader loader = this.loader(transports, null);
        ConnectorConfig config = config("custom-protocol");

        // 发现阶段拒绝重复名称，不进入连接器创建。
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            this.manager(loader, config).close();
        });
        assertTrue(failure.getMessage().contains("Duplicate transport SPI name"));
        assertEquals(0, CustomTransport.created.get());
    }

    @Test
    public void protocolNamesMustBeUniqueIgnoringCase() throws Exception {
        // 两个协议 SPI 仅以名称大小写区别。
        Class<?>[] protocols = { CustomProtocol.class, DuplicateProtocol.class };
        ClassLoader loader = this.loader(null, protocols);
        ConnectorConfig config = config("custom-protocol");

        // 发现阶段拒绝重复协议名。
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            this.manager(loader, config).close();
        });
        assertTrue(failure.getMessage().contains("Duplicate protocol SPI name"));
        assertEquals(0, CustomTransport.created.get());
    }

    @Test
    public void blankProtocolNameIsRejectedDuringDiscovery() throws Exception {
        // SPI 提供者返回空白协议名称。
        Class<?>[] protocols = { BlankProtocol.class };
        ClassLoader loader = this.loader(null, protocols);
        ConnectorConfig config = config("custom-protocol");

        // 缺少名称时不能建立可选择的协议表。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.manager(loader, config).close();
        });
        assertTrue(failure.getMessage().contains("Missing protocol SPI name"));
        assertEquals(0, CustomTransport.created.get());
    }

    @Test
    public void missingTransportIsRejectedDuringPreparation() throws Exception {
        // 协议存在，但类加载器没有暴露它依赖的传输。
        Class<?>[] protocols = { CustomProtocol.class };
        ClassLoader loader = this.loader(new Class<?>[0], protocols);
        ConnectorConfig config = config("custom-protocol");

        // 错误包含缺失的传输名，失败不分配网络资源。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.manager(loader, config).close();
        });
        assertTrue(failure.getMessage().contains("Unknown transport: custom"));
        assertEquals(0, CustomTransport.created.get());
    }

    private static ConnectorConfig config(String protocol) {
        InterAddress localAddress = InterAddress.forBinding("custom", "127.0.0.1", 0, "test");
        ProtocolConfig mount = new ProtocolConfig("custom", "custom", protocol, Collections.emptyMap());
        Map<String, String> options = Map.of("listenType", "custom", "workerThread", "1");
        List<ProtocolConfig> mounts = Collections.singletonList(mount);
        return new ConnectorConfig("test", localAddress, options, mounts, true);
    }

    private ConnectorManager manager(ClassLoader loader, ConnectorConfig config) {
        // 单元夹具仅暴露配置与类加载器，网络实现仍由真实 SPI 创建。
        InvocationHandler settingsHandler = (proxy, method, args) -> {
            if (StringUtils.equals(method.getName(), "getConnectorConfigs")) {
                return Collections.singletonList(config);
            }
            throw new UnsupportedOperationException(method.getName());
        };
        Class<?>[] settingTypes = { MetSettings.class };
        MetSettings settings = (MetSettings) Proxy.newProxyInstance(loader, settingTypes, settingsHandler);
        InvocationHandler contextHandler = (proxy, method, args) -> {
            if (StringUtils.equals(method.getName(), "getSettings")) {
                return settings;
            }
            if (StringUtils.equals(method.getName(), "getClassLoader")) {
                return loader;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        Class<?>[] contextTypes = { MetContext.class };
        MetContext context = (MetContext) Proxy.newProxyInstance(loader, contextTypes, contextHandler);

        // 与正式启动相同，先发现 SPI 再规范化配置，最后创建管理器。
        EndpointConnectorFactory factory = new EndpointConnectorFactory();
        factory.listenTypes(loader);
        factory.prepare(config);
        return new ConnectorManager(context, factory);
    }

    private ClassLoader loader(Class<?>[] transports, Class<?>[] protocols) throws Exception {
        // 只替换当前用例指定的 SPI 描述文件，其余资源沿用父加载器。
        Map<String, URL> descriptors = new HashMap<>();
        this.descriptor(descriptors, NetworkConnectorFactory.class, transports);
        this.descriptor(descriptors, ProtocolFactory.class, protocols);
        return new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                URL descriptor = descriptors.get(name);
                if (descriptor == null) {
                    return super.getResources(name);
                }
                return Collections.enumeration(Collections.singletonList(descriptor));
            }
        };
    }

    private void descriptor(Map<String, URL> descriptors, Class<?> service, Class<?>[] providers) throws Exception {
        if (providers == null) {
            return;
        }
        Path descriptor = this.temporary.newFile().toPath();
        Files.write(descriptor, Arrays.stream(providers).map(Class::getName).toList());
        descriptors.put("META-INF/services/" + service.getName(), descriptor.toUri().toURL());
    }

    public static class CustomTransport implements NetworkConnectorFactory<byte[]> {
        private static final AtomicInteger created  = new AtomicInteger();
        private static final AtomicInteger prepared = new AtomicInteger();

        public void configRoutes(Map<String, Map<String, String>> routes) {
            prepared.incrementAndGet();
            for (Map<String, String> options : routes.values()) {
                options.put("preparation", "ready");
            }
        }

        public String name() {
            return "CUSTOM";
        }

        public Class<byte[]> messageType() {
            return byte[].class;
        }

        public NetworkConnector<byte[]> create(ConnectorConfig config, ClassLoader loader) {
            created.incrementAndGet();
            return new TcpConnector(config, loader);
        }
    }

    public static class DuplicateTransport extends CustomTransport {
        public String name() {
            return "custom";
        }
    }

    public static class CustomProtocol extends RawSocketTestProtocol {
        private static final BlockingQueue<ProtocolConfig> configurations = new LinkedBlockingQueue<>();

        public ProtocolSession<byte[]> create( //
                ProtocolConfig config, //
                ProtocolContext context, //
                NetworkChannel<byte[]> connection, //
                BiConsumer<Long, Payload> messages) {
            configurations.add(config);
            return super.create(config, context, connection, messages);
        }

        public String name() {
            return "CUSTOM-PROTOCOL";
        }

        public String transport() {
            return "custom";
        }
    }

    public static class DuplicateProtocol extends CustomProtocol {
        public String name() {
            return "custom-protocol";
        }
    }

    public static class BlankProtocol extends CustomProtocol {
        public String name() {
            return " ";
        }
    }

    public static class WrongMessageProtocol extends TextProtocol {
        public String name() {
            return "wrong-message";
        }

        public String transport() {
            return "custom";
        }
    }
}
