/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.example.connector.protocol.EndpointTestProtocols.DatagramWireA;
import net.hasor.example.connector.protocol.EndpointTestProtocols.HttpA;
import net.hasor.example.connector.protocol.EndpointTestProtocols.HttpB;
import net.hasor.example.connector.protocol.EndpointTestProtocols.WireA;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.tcp.TcpProtocolProvider;
import net.hasor.meteor.connector.transport.udp.UdpProtocolProvider;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 共享字节监听需要协议探测；纯出站端点直接选定协议，HTTP 按路径准备挂载。 */
public class PlanCompilerTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void oneTcpProtocolDoesNotRequireTransportProbing() throws Exception {
        // 只有一个 TCP 协议时，不需要按报文选择分支。
        PlanCompiler compiler = this.compiler(PlainTcpProtocol.class);
        ConnectorConfig config = config("tcp", true, "plain-tcp");

        // 准备只合并配置，不创建网络会话或服务绑定。
        compiler.prepareAll(List.of(config));
        assertEquals("Json", config.protocol("plain-tcp").option("serializeType", null));
    }

    @Test
    public void oneUdpProtocolDoesNotRequireTransportProbing() throws Exception {
        // UDP 的单协议端点同样可以直接使用普通协议提供者。
        PlanCompiler compiler = this.compiler(PlainUdpProtocol.class);
        ConnectorConfig config = config("udp", true, "plain-udp");

        // 单个协议的默认编码可以正常进入准备结果。
        compiler.prepareAll(List.of(config));
        assertEquals("Json", config.protocol("plain-udp").option("serializeType", null));
    }

    @Test
    public void sharedTcpEndpointRejectsALaterProtocolWithoutProbing() throws Exception {
        // 第一个协议具备探测能力，后续普通协议不能混入共享字节端点。
        PlanCompiler compiler = this.compiler(WireA.class, PlainTcpProtocol.class);
        ConnectorConfig config = config("tcp", true, "wirea", "plain-tcp");

        // 准备阶段明确指出缺少探测的协议，不延迟到收到报文后失败。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> compiler.prepareAll(List.of(config)));
        assertEquals("Shared byte transport requires protocol probing: plain-tcp", failure.getMessage());
    }

    @Test
    public void sharedUdpEndpointRejectsAProtocolWithoutProbing() throws Exception {
        // UDP 即使支持共享物理通道，多协议报文也需要可执行的探测入口。
        PlanCompiler compiler = this.compiler(PlainUdpProtocol.class, DatagramWireA.class);
        ConnectorConfig config = config("udp", true, "plain-udp", "datagramwirea");

        // 失败发生在配置准备阶段，无需创建 Socket 或尝试收包。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> compiler.prepareAll(List.of(config)));
        assertEquals("Shared byte transport requires protocol probing: plain-udp", failure.getMessage());
    }

    @Test
    public void sharedTcpEndpointRejectsAProviderWithOnlyUdpProbing() throws Exception {
        // 消息类型同为 byte[]，但 UDP 探测契约不能用于 TCP 监听。
        PlanCompiler compiler = this.compiler(WireA.class, TcpWithUdpProbe.class);
        ConnectorConfig config = config("tcp", true, "wirea", "tcp-with-udp-probe");

        // 配置中的传输名仍为 TCP，错误必须指向缺失的 TCP 探测能力。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> compiler.prepareAll(List.of(config)));
        String expected = "Shared byte transport requires protocol probing: tcp-with-udp-probe";
        assertEquals(expected, failure.getMessage());
    }

    @Test
    public void sharedUdpEndpointRejectsAProviderWithOnlyTcpProbing() throws Exception {
        // 普通 UDP 提供者只额外实现 TCP 探测，不能因此取得 UDP 探测资格。
        PlanCompiler compiler = this.compiler(UdpWithTcpProbe.class, PlainUdpProtocol.class);
        ConnectorConfig config = config("udp", true, "udp-with-tcp-probe", "plain-udp");

        // 准备阶段按照实际 UDP 传输检查专属接口，不仅判断是否存在同名方法。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> compiler.prepareAll(List.of(config)));
        String expected = "Shared byte transport requires protocol probing: udp-with-tcp-probe";
        assertEquals(expected, failure.getMessage());
    }

    @Test
    public void clientOnlyTcpEndpointDoesNotRequireProbingForMultipleProtocols() throws Exception {
        // 纯出站端点在 connect 时已选定协议，不需要按收到的字节猜测分支。
        PlanCompiler compiler = this.compiler(WireA.class, PlainTcpProtocol.class);
        ConnectorConfig config = config("tcp", false, "wirea", "plain-tcp");

        // 同时准备两个协议，普通提供者仍可作为明确指定的出站协议。
        compiler.prepareAll(List.of(config));
        assertEquals("Java", config.protocol("wirea").option("serializeType", null));
        assertEquals("Json", config.protocol("plain-tcp").option("serializeType", null));
    }

    @Test
    public void clientOnlyUdpEndpointDoesNotRequireProbingForMultipleProtocols() throws Exception {
        // 未开启监听的 UDP 端点也能按调用方指定的协议发起连接。
        PlanCompiler compiler = this.compiler(PlainUdpProtocol.class, DatagramWireA.class);
        ConnectorConfig config = config("udp", false, "plain-udp", "datagramwirea");

        // 准备结果分别保留各协议的编码，不要求纯出站协议实现探测扩展。
        compiler.prepareAll(List.of(config));
        assertEquals("Json", config.protocol("plain-udp").option("serializeType", null));
        assertEquals("Java", config.protocol("datagramwirea").option("serializeType", null));
    }

    @Test
    public void sharedHttpEndpointUsesContextsWithoutTransportProbing() throws Exception {
        // HTTP 普通协议提供者按上下文路径共享端口，不实现字节探测扩展。
        PlanCompiler compiler = this.compiler(HttpA.class, HttpB.class);
        ConnectorConfig config = config("http", true, "httpa", "httpb");

        // 同一端点为两个协议分别准备路径，保留各自挂载的选择边界。
        compiler.prepareAll(List.of(config));
        assertEquals("/httpa", config.protocol("httpa").option("contextPath", null));
        assertEquals("/httpb", config.protocol("httpb").option("contextPath", null));
    }

    private PlanCompiler compiler(Class<?>... providers) throws Exception {
        // 仅替换协议 SPI 描述，正式 TCP、UDP、HTTP 传输仍由原有资源发现。
        List<String> names = new ArrayList<>();
        for (Class<?> provider : providers) {
            names.add(provider.getName());
        }
        Path descriptor = this.temporary.newFile().toPath();
        Files.write(descriptor, names);
        URL resource = descriptor.toUri().toURL();
        String serviceResource = "META-INF/services/" + ProtocolProvider.class.getName();
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (StringUtils.equals(name, serviceResource)) {
                    return Collections.enumeration(List.of(resource));
                }
                return super.getResources(name);
            }
        };
        PlanCompiler compiler = new PlanCompiler();
        compiler.discover(loader);
        return compiler;
    }

    private static ConnectorConfig config(String transport, boolean bindEnabled, String... protocols) {
        // 顺序用于验证共享端点会检查全部协议，而不是只判断第一个分支。
        List<ProtocolConfig> mounts = new ArrayList<>();
        for (String protocol : protocols) {
            mounts.add(ProtocolConfig.newConfig(protocol, protocol, Map.of()));
        }
        InterAddress address = InterAddress.forBinding(transport, "127.0.0.1", 0, "planned");
        return new ConnectorConfig("planned", address, Map.of(), mounts, bindEnabled);
    }

    public static class PlainTcpProtocol implements ProtocolProvider<byte[]> {
        @Override
        public String name() {
            return "plain-tcp";
        }

        @Override
        public String transportType() {
            return "tcp";
        }

        @Override
        public String defaultSerialize() {
            return "Json";
        }

        @Override
        public Class<byte[]> messageType() {
            return byte[].class;
        }

        @Override
        public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
            throw new AssertionError("Planning must not prepare service bindings");
        }

        @Override
        public ProtocolSession<byte[]> createSession(ProtocolConfig config, ProtocolSessionContext context, //
                NetworkChannel<byte[]> connection, ObjLongConsumer<Payload> messages) {
            throw new AssertionError("Planning must not create network sessions");
        }
    }

    public static class PlainUdpProtocol extends PlainTcpProtocol {
        @Override
        public String name() {
            return "plain-udp";
        }

        @Override
        public String transportType() {
            return "udp";
        }
    }

    public static class TcpWithUdpProbe extends PlainTcpProtocol implements UdpProtocolProvider {
        @Override
        public String name() {
            return "tcp-with-udp-probe";
        }

        @Override
        public RouteProbeResult probeProtocol(ProtocolConfig config, byte[] message) {
            throw new AssertionError("Planning must not execute protocol probing");
        }
    }

    public static class UdpWithTcpProbe extends PlainUdpProtocol implements TcpProtocolProvider {
        @Override
        public String name() {
            return "udp-with-tcp-probe";
        }

        @Override
        public RouteProbeResult probeProtocol(ProtocolConfig config, byte[] message) {
            throw new AssertionError("Planning must not execute protocol probing");
        }
    }
}
