/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.oncrpc.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.oncrpc.codec.OncRpcException;
import net.hasor.meteor.protocol.oncrpc.codec.OncRpcMessage;
import org.junit.Test;
import static org.junit.Assert.*;

/** rpcgen 生成的 C 客户端与 libtirpc 服务端经真实 TCP、UDP 与 Meteor 双向互通。 */
public class OncRpcOfficialInteropScenarioTest {
    @Test(timeout = 30000)
    public void nativeTcpEchoCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=echo", "--value=native-to-Meteor")) {
                peer.requireResult("echo=native-to-Meteor");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpArgumentOrderCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=add")) {
                peer.requireResult("add=1923");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpAllXdrTypesCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=mirror")) {
                peer.requireResult("mirror=ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpVoidResultCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=ping")) {
                peer.requireResult("ping=ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpVariableOpaqueCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=bytes")) {
                peer.requireResult("bytes=257");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpNullProcedureCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=null")) {
                peer.requireResult("null=ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpUnknownProgramCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=wrong-program")) {
                peer.requireExpectedError("wrong-program=RPC_PROGUNAVAIL");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpUnknownVersionCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=wrong-version")) {
                peer.requireExpectedError("wrong-version=RPC_PROGVERSMISMATCH");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpUnknownProcedureCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=wrong-procedure")) {
                peer.requireExpectedError("wrong-procedure=RPC_PROCUNAVAIL");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpMalformedArgumentsCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=bad-arguments")) {
                peer.requireExpectedError("bad-arguments=RPC_CANTDECODEARGS");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpApplicationFailureCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=crash")) {
                peer.requireExpectedError("crash=RPC_SYSTEMERROR");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpUnsupportedCredentialCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=tcp", //
                    "--port=" + port, "--scenario=unsupported-auth")) {
                peer.requireExpectedError("unsupported-auth=RPC_AUTHERROR");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpSystemCredentialAccepted() throws Exception {
        // 当前监听挂载要求 AUTH_SYS，普通 RPC 方法仍按原有 Caller 链路执行。
        MetConfiguration configuration = this.requireSystem("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 一次原生调用验证该凭证策略，超时不视为认证拒绝。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", //
                    "--transport=tcp", "--port=" + port, "--auth=sys", //
                    "--scenario=echo", "--value=authenticated")) {
                peer.requireResult("echo=authenticated");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeTcpMissingSystemCredentialRejected() throws Exception {
        // 当前监听挂载要求 AUTH_SYS，普通 RPC 方法仍按原有 Caller 链路执行。
        MetConfiguration configuration = this.requireSystem("tcp", "oncrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 一次原生调用验证该凭证策略，超时不视为认证拒绝。
            int port = server.getListenAddress("oncrpc").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", //
                    "--transport=tcp", "--port=" + port, "--auth=none", //
                    "--scenario=weak-auth", "--value=authenticated")) {
                peer.requireExpectedError("weak-auth=RPC_AUTHERROR");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorTcpEchoCallsNative() throws Exception {
        // 原生服务端使用 rpcgen dispatcher；Meteor 只组装出站通道。
        MetConfiguration configuration = this.outgoing("tcp", "oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc", "127.0.0.1", port, unitName);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 业务结果来自独立 C 服务端，当前客户端不存在可供本地调用的实现。
            String value = "Meteor-to-native";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void meteorTcpArgumentOrderCallsNative() throws Exception {
        // 原生服务端使用 rpcgen dispatcher；Meteor 只组装出站通道。
        MetConfiguration configuration = this.outgoing("tcp", "oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc", "127.0.0.1", port, unitName);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 业务结果来自独立 C 服务端，当前客户端不存在可供本地调用的实现。
            assertEquals(1923, remote.add(19, 23));
        }
    }

    @Test(timeout = 30000)
    public void meteorTcpVoidResultCallsNative() throws Exception {
        // 原生服务端使用 rpcgen dispatcher；Meteor 只组装出站通道。
        MetConfiguration configuration = this.outgoing("tcp", "oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc", "127.0.0.1", port, unitName);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 业务结果来自独立 C 服务端，当前客户端不存在可供本地调用的实现。
            remote.ping();
        }
    }

    @Test(timeout = 30000)
    public void meteorTcpVariableOpaqueCallsNative() throws Exception {
        // 原生服务端使用 rpcgen dispatcher；Meteor 只组装出站通道。
        MetConfiguration configuration = this.outgoing("tcp", "oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc", "127.0.0.1", port, unitName);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 业务结果来自独立 C 服务端，当前客户端不存在可供本地调用的实现。
            byte[] value = { 0, 1, 127, -128, -1, 42, 11 };
            assertArrayEquals(value, remote.bytes(value));
        }
    }

    @Test(timeout = 30000)
    public void meteorTcpAllXdrTypesCallsNative() throws Exception {
        // 原生服务端使用 rpcgen dispatcher；Meteor 只组装出站通道。
        MetConfiguration configuration = this.outgoing("tcp", "oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc", "127.0.0.1", port, unitName);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 业务结果来自独立 C 服务端，当前客户端不存在可供本地调用的实现。
            OncRpcSample expected = this.sample();
            OncRpcSample actual = remote.mirror(expected);
            assertEquals(expected.getSignedValue(), actual.getSignedValue());
            assertEquals(expected.getUnsignedValue(), actual.getUnsignedValue());
            assertEquals(expected.getSignedHyper(), actual.getSignedHyper());
            assertEquals(expected.getUnsignedHyper(), actual.getUnsignedHyper());
            assertEquals(expected.getFloatValue(), actual.getFloatValue(), 0);
            assertEquals(expected.getDoubleValue(), actual.getDoubleValue(), 0);
            assertEquals(expected.getBoolValue(), actual.getBoolValue());
            assertEquals(expected.getText(), actual.getText());
            assertArrayEquals(expected.getFixedOpaque(), actual.getFixedOpaque());
            assertArrayEquals(expected.getBytes(), actual.getBytes());
            assertEquals(expected.getFixedNumbers(), actual.getFixedNumbers());
            assertEquals(expected.getNumbers(), actual.getNumbers());
            assertEquals(expected.getColour(), actual.getColour());
            assertEquals(expected.getChoice().getTag(), actual.getChoice().getTag());
            assertEquals(expected.getChoice().getText(), actual.getChoice().getText());
            assertEquals(expected.getOptional().getValue(), actual.getOptional().getValue());
            assertEquals(expected.getOptional().getNext().getValue(), actual.getOptional().getNext().getValue());
            assertNull(actual.getOptional().getNext().getNext());
        }
    }

    @Test(timeout = 30000)
    public void meteorTcpReadsNativeSystemError() throws Exception {
        // 原生过程明确返回 svcerr_systemerr，Meteor 通过正常公开客户端读取错误。
        MetConfiguration configuration = this.outgoing("tcp", "oncrpc");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            InterAddress address = new InterAddress("oncrpc", "127.0.0.1", port, //
                    client.getSettings().getUnitName());
            MetClient caller = client.getRsfClient(address);

            // 同步 API 的原始失败原因应保留标准 SYSTEM_ERR，而不是转为超时。
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> caller.syncInvoke(service, "crash", new Class<?>[0], new Object[0]));
            assertTrue(failure.getCause() instanceof OncRpcException);
            OncRpcException rpcFailure = (OncRpcException) failure.getCause();
            assertEquals(OncRpcMessage.SYSTEM_ERR, rpcFailure.getAcceptStatus());
        }
    }

    @Test(timeout = 30000)
    public void meteorTcpSendsSystemIdentityToNative() throws Exception {
        // AUTH_SYS 只是声明身份；当前场景核对实际发给 libtirpc 的 UID、GID 和附加组。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.setQueueMinPoolSize(2).setDefaultTimeout(5000);
        configuration.connector("tcp").protocol("oncrpc") //
                .option("auth", "sys").option("authMachine", "meteor-test") //
                .option("authUid", 123).option("authGid", 456).option("authGroups", "7,11");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=tcp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            InterAddress address = new InterAddress("oncrpc", "127.0.0.1", port, //
                    client.getSettings().getUnitName());
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 原生服务直接从 svc_req 读取凭证，未借用 Meteor 的解码实现。
            assertEquals("sys:123:456:7,11", remote.auth());
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpEchoCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=echo", "--value=native-to-Meteor")) {
                peer.requireResult("echo=native-to-Meteor");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpArgumentOrderCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=add")) {
                peer.requireResult("add=1923");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpAllXdrTypesCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=mirror")) {
                peer.requireResult("mirror=ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpVoidResultCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=ping")) {
                peer.requireResult("ping=ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpVariableOpaqueCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=bytes")) {
                peer.requireResult("bytes=257");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpNullProcedureCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=null")) {
                peer.requireResult("null=ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpUnknownProgramCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=wrong-program")) {
                peer.requireExpectedError("wrong-program=RPC_PROGUNAVAIL");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpUnknownVersionCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=wrong-version")) {
                peer.requireExpectedError("wrong-version=RPC_PROGVERSMISMATCH");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpUnknownProcedureCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=wrong-procedure")) {
                peer.requireExpectedError("wrong-procedure=RPC_PROCUNAVAIL");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpMalformedArgumentsCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=bad-arguments")) {
                peer.requireExpectedError("bad-arguments=RPC_CANTDECODEARGS");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpApplicationFailureCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=crash")) {
                peer.requireExpectedError("crash=RPC_SYSTEMERROR");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpUnsupportedCredentialCallsMeteor() throws Exception {
        // 监听器和服务均通过正式 bootstrap API 创建，发布发生在启动之后。
        MetConfiguration configuration = this.incoming("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 原生进程只执行当前场景，标准错误必须由 libtirpc 确认。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", "--transport=udp", //
                    "--port=" + port, "--scenario=unsupported-auth")) {
                peer.requireExpectedError("unsupported-auth=RPC_AUTHERROR");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpSystemCredentialAccepted() throws Exception {
        // 当前监听挂载要求 AUTH_SYS，普通 RPC 方法仍按原有 Caller 链路执行。
        MetConfiguration configuration = this.requireSystem("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 一次原生调用验证该凭证策略，超时不视为认证拒绝。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", //
                    "--transport=udp", "--port=" + port, "--auth=sys", //
                    "--scenario=echo", "--value=authenticated")) {
                peer.requireResult("echo=authenticated");
            }
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpMissingSystemCredentialRejected() throws Exception {
        // 当前监听挂载要求 AUTH_SYS，普通 RPC 方法仍按原有 Caller 链路执行。
        MetConfiguration configuration = this.requireSystem("udp", "oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 一次原生调用验证该凭证策略，超时不视为认证拒绝。
            int port = server.getListenAddress("oncrpc-udp").getPort();
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", //
                    "--transport=udp", "--port=" + port, "--auth=none", //
                    "--scenario=weak-auth", "--value=authenticated")) {
                peer.requireExpectedError("weak-auth=RPC_AUTHERROR");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorUdpEchoCallsNative() throws Exception {
        // 原生服务端使用 rpcgen dispatcher；Meteor 只组装出站通道。
        MetConfiguration configuration = this.outgoing("udp", "oncrpc-udp");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=udp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc-udp", "127.0.0.1", port, unitName);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 业务结果来自独立 C 服务端，当前客户端不存在可供本地调用的实现。
            String value = "Meteor-to-native";
            assertEquals(value, remote.echo(value));
        }
    }

    @Test(timeout = 30000)
    public void meteorUdpArgumentOrderCallsNative() throws Exception {
        // 原生服务端使用 rpcgen dispatcher；Meteor 只组装出站通道。
        MetConfiguration configuration = this.outgoing("udp", "oncrpc-udp");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=udp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc-udp", "127.0.0.1", port, unitName);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 业务结果来自独立 C 服务端，当前客户端不存在可供本地调用的实现。
            assertEquals(1923, remote.add(19, 23));
        }
    }

    @Test(timeout = 30000)
    public void meteorUdpVoidResultCallsNative() throws Exception {
        // 原生服务端使用 rpcgen dispatcher；Meteor 只组装出站通道。
        MetConfiguration configuration = this.outgoing("udp", "oncrpc-udp");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=udp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc-udp", "127.0.0.1", port, unitName);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 业务结果来自独立 C 服务端，当前客户端不存在可供本地调用的实现。
            remote.ping();
        }
    }

    @Test(timeout = 30000)
    public void meteorUdpVariableOpaqueCallsNative() throws Exception {
        // 原生服务端使用 rpcgen dispatcher；Meteor 只组装出站通道。
        MetConfiguration configuration = this.outgoing("udp", "oncrpc-udp");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=udp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc-udp", "127.0.0.1", port, unitName);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 业务结果来自独立 C 服务端，当前客户端不存在可供本地调用的实现。
            byte[] value = { 0, 1, 127, -128, -1, 42, 11 };
            assertArrayEquals(value, remote.bytes(value));
        }
    }

    @Test(timeout = 30000)
    public void meteorUdpAllXdrTypesCallsNative() throws Exception {
        // 原生服务端使用 rpcgen dispatcher；Meteor 只组装出站通道。
        MetConfiguration configuration = this.outgoing("udp", "oncrpc-udp");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=udp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            String unitName = client.getSettings().getUnitName();
            InterAddress address = new InterAddress("oncrpc-udp", "127.0.0.1", port, unitName);
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 业务结果来自独立 C 服务端，当前客户端不存在可供本地调用的实现。
            OncRpcSample expected = this.sample();
            OncRpcSample actual = remote.mirror(expected);
            assertEquals(expected.getSignedValue(), actual.getSignedValue());
            assertEquals(expected.getUnsignedValue(), actual.getUnsignedValue());
            assertEquals(expected.getSignedHyper(), actual.getSignedHyper());
            assertEquals(expected.getUnsignedHyper(), actual.getUnsignedHyper());
            assertEquals(expected.getFloatValue(), actual.getFloatValue(), 0);
            assertEquals(expected.getDoubleValue(), actual.getDoubleValue(), 0);
            assertEquals(expected.getBoolValue(), actual.getBoolValue());
            assertEquals(expected.getText(), actual.getText());
            assertArrayEquals(expected.getFixedOpaque(), actual.getFixedOpaque());
            assertArrayEquals(expected.getBytes(), actual.getBytes());
            assertEquals(expected.getFixedNumbers(), actual.getFixedNumbers());
            assertEquals(expected.getNumbers(), actual.getNumbers());
            assertEquals(expected.getColour(), actual.getColour());
            assertEquals(expected.getChoice().getTag(), actual.getChoice().getTag());
            assertEquals(expected.getChoice().getText(), actual.getChoice().getText());
            assertEquals(expected.getOptional().getValue(), actual.getOptional().getValue());
            assertEquals(expected.getOptional().getNext().getValue(), actual.getOptional().getNext().getValue());
            assertNull(actual.getOptional().getNext().getNext());
        }
    }

    @Test(timeout = 30000)
    public void meteorUdpReadsNativeSystemError() throws Exception {
        // 原生过程明确返回 svcerr_systemerr，Meteor 通过正常公开客户端读取错误。
        MetConfiguration configuration = this.outgoing("udp", "oncrpc-udp");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=udp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            InterAddress address = new InterAddress("oncrpc-udp", "127.0.0.1", port, //
                    client.getSettings().getUnitName());
            MetClient caller = client.getRsfClient(address);

            // 同步 API 的原始失败原因应保留标准 SYSTEM_ERR，而不是转为超时。
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> caller.syncInvoke(service, "crash", new Class<?>[0], new Object[0]));
            assertTrue(failure.getCause() instanceof OncRpcException);
            OncRpcException rpcFailure = (OncRpcException) failure.getCause();
            assertEquals(OncRpcMessage.SYSTEM_ERR, rpcFailure.getAcceptStatus());
        }
    }

    @Test(timeout = 30000)
    public void meteorUdpSendsSystemIdentityToNative() throws Exception {
        // AUTH_SYS 只是声明身份；当前场景核对实际发给 libtirpc 的 UID、GID 和附加组。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.setQueueMinPoolSize(2).setDefaultTimeout(5000);
        configuration.connector("udp").protocol("oncrpc-udp") //
                .option("auth", "sys").option("authMachine", "meteor-test") //
                .option("authUid", 123).option("authGid", 456).option("authGroups", "7,11");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=udp", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            InterAddress address = new InterAddress("oncrpc-udp", "127.0.0.1", port, //
                    client.getSettings().getUnitName());
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 原生服务直接从 svc_req 读取凭证，未借用 Meteor 的解码实现。
            assertEquals("sys:123:456:7,11", remote.auth());
        }
    }

    @Test(timeout = 30000)
    public void nativeUdpLargeOpaqueCallsMeteorOverIpv6() throws Exception {
        // 使用 IPv6 回环承载单个 50,000 字节数据报，避免本机 IPv4 大包被环境丢弃。
        int port;
        try (DatagramSocket reservation = new DatagramSocket(0, InetAddress.getByName("::1"))) {
            port = reservation.getLocalPort();
        }
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.setQueueMinPoolSize(2).setDefaultTimeout(5000);
        configuration.connector("udp").bind("::1", port).protocol("oncrpc-udp");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // libtirpc 生成、发送并逐字节核对完整 opaque 正文，没有分拆为多条 RPC。
            try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=client", //
                    "--transport=udp", "--host=::1", "--port=" + port, //
                    "--scenario=bytes", "--value=50000")) {
                peer.requireResult("bytes=50000");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorUdpLargeOpaqueCallsNativeOverIpv6() throws Exception {
        // 原生 UDP 监听器绑定 IPv6，Meteor 通过正式出站协议配置调用。
        MetConfiguration configuration = this.outgoing("udp", "oncrpc-udp");
        try (OncRpcNativeProcess peer = OncRpcNativeProcess.start("--command=server", //
                "--transport=udp", "--host=::1", "--port=0"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            InterAddress address = new InterAddress("oncrpc-udp", "::1", port, client.getSettings().getUnitName());
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);

            // 一个调用传递一个完整大数据报，所有字节必须按原值返回。
            byte[] value = new byte[50000];
            for (int index = 0; index < value.length; index++) {
                value[index] = (byte) (index * 31 + 7);
            }
            assertArrayEquals(value, remote.bytes(value));
        }
    }

    private MetConfiguration incoming(String transport, String protocol) throws IOException {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(protocol);
        configuration.setQueueMinPoolSize(2).setDefaultTimeout(5000);
        int port = this.listenPort(transport);
        configuration.connector(transport).bind("127.0.0.1", port).protocol(protocol);
        return configuration;
    }

    private MetConfiguration outgoing(String transport, String protocol) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(protocol);
        configuration.setQueueMinPoolSize(2).setDefaultTimeout(5000);
        configuration.connector(transport).protocol(protocol);
        return configuration;
    }

    private MetConfiguration requireSystem(String transport, String protocol) throws IOException {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(protocol);
        configuration.setQueueMinPoolSize(2).setDefaultTimeout(5000);
        int port = this.listenPort(transport);
        configuration.connector(transport).bind("127.0.0.1", port).protocol(protocol).option("auth", "require-sys");
        return configuration;
    }

    private int listenPort(String transport) throws IOException {
        if (!StringUtils.equals(transport, "udp")) {
            return 0;
        }
        // UDP 公开监听入口要求具体端口，临时端口由操作系统分配。
        try (DatagramSocket reservation = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            return reservation.getLocalPort();
        }
    }

    private OncRpcSample sample() {
        // 只准备业务数据，字段顺序由公开绑定资源中的应用 XDR 映射器确定。
        OncRpcSample value = new OncRpcSample();
        value.setSignedValue(-123456789);
        value.setUnsignedValue(0xffffffffL);
        value.setSignedHyper(-9_000_000_000L);
        value.setUnsignedHyper(new BigInteger("18446744073709551615"));
        value.setFloatValue(1.25f);
        value.setDoubleValue(-12345.625);
        value.setBoolValue(true);
        value.setText("Meteor-XDR-sample");
        value.setFixedOpaque(new byte[] { 0, 1, -1, 42, 127 });
        value.setBytes(new byte[] { -128, 0, -1, 25, 42, 21, 12 });
        value.setFixedNumbers(List.of(-1, 0, Integer.MAX_VALUE));
        value.setNumbers(List.of(7, -9, 11, 25));
        value.setColour(5);
        OncRpcChoice choice = new OncRpcChoice();
        choice.setTag(2);
        choice.setText("union-text");
        value.setChoice(choice);
        OncRpcOptional first = new OncRpcOptional();
        first.setValue(17);
        OncRpcOptional second = new OncRpcOptional();
        second.setValue(-23);
        first.setNext(second);
        value.setOptional(first);
        return value;
    }
}
