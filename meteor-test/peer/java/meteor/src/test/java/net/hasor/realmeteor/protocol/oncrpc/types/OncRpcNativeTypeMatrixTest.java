/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc.types;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.example.protocol.oncrpc.OncRpcSample;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.protocol.oncrpc.OncRpcNativeProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** rpcgen/libtirpc 以独立 C 容器验证每个 XDR 类型边界，TCP 和 UDP 分别展开。 */
@ParameterizedClass(autoCloseArguments = false, name = "{0}/{1}", quoteTextArguments = false)
@MethodSource("parameters")
@NxnRpc(name = "oncrpc", version = "2")
public class OncRpcNativeTypeMatrixTest {
    private final String transport;
    private final String vector;

    public OncRpcNativeTypeMatrixTest(String transport, String vector) {
        this.transport = transport;
        this.vector = vector;
    }
    public static Collection<Object[]> parameters() {
        // 类型顺序固定，每条向量和传输组合均产生独立的 provider、consumer 实例。
        List<Object[]> parameters = new ArrayList<>();
        for (String vector : OncRpcTypeVectors.names()) {
            parameters.add(new Object[] { "tcp", vector });
            parameters.add(new Object[] { "udp", vector });
        }
        return parameters;
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorProviderAcceptsNativeType() throws Exception {
        // 从公开入口启动提供者；UDP 的具体监听端口由操作系统临时分配。
        String protocol = this.protocol();
        MetConfiguration configuration = this.configuration(protocol);
        int listenPort = 0;
        if (StringUtils.equals(this.transport, "udp")) {
            InetAddress runner = InetAddress.getByName(ContainerProcess.runnerHost());
            try (DatagramSocket reservation = new DatagramSocket(0, runner)) {
                listenPort = reservation.getLocalPort();
            }
        }
        configuration.connector(this.transport).bind(ContainerProcess.runnerHost(), listenPort).protocol(protocol);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource(protocol, OncRpcDemoCodecs.resource()).register();

            // C 自己构造当前类型的真实 XDR 参数，并逐字段核对当前向量的返回值。
            int port = server.getListenAddress(protocol).getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", //
                    "--transport=" + this.transport, "--port=" + port, //
                    "--scenario=types", "--value=" + this.vector)) {
                if (StringUtils.equals(this.vector, "string-unicode-rejected")) {
                    peer.requireExpectedError("types-unicode=RPC_CANTDECODEARGS");
                } else {
                    peer.requireResult("types=" + this.vector);
                }
            }
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorConsumerReadsNativeType() throws Exception {
        // 独立 libtirpc 提供者保持既有 mirror 过程，Meteor 仅创建出站连接器。
        String protocol = this.protocol();
        MetConfiguration configuration = this.configuration(protocol);
        configuration.connector(this.transport).protocol(protocol);
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=" + this.transport, "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource(protocol, OncRpcDemoCodecs.resource()).register();
            InterAddress address = new InterAddress(protocol, peer.host(), port, client.getSettings().getUnitName());
            MetClient caller = client.getRsfClient(address);
            OncRpcSample expected = OncRpcTypeVectors.sample(this.vector);

            // 标准 string 的 Unicode 限制以精确编码错误呈现；成功向量只检查当前字段。
            if (StringUtils.equals(this.vector, "string-unicode-rejected")) {
                Class<?>[] types = { OncRpcSample.class };
                Object[] arguments = { expected };
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> caller.syncInvoke(service, "mirror", types, arguments));
                Throwable cause = failure;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                assertTrue(cause instanceof IllegalArgumentException);
                assertEquals("XDR string must contain ASCII characters", cause.getMessage());
            } else {
                OncRpcSample actual = caller.getRemote(service).mirror(expected);
                OncRpcTypeVectors.assertField(this.vector, expected, actual);
            }
        }
    }

    private String protocol() {
        if (StringUtils.equals(this.transport, "udp")) {
            return "oncrpc-udp";
        }
        return "oncrpc";
    }

    private MetConfiguration configuration(String protocol) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(protocol);
        configuration.setQueueMinPoolSize(2).setDefaultTimeout(5000);
        return configuration;
    }
}
