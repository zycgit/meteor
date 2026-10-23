/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import org.acplt.oncrpc.OncRpcException;
import org.acplt.oncrpc.XdrString;
import org.acplt.oncrpc.XdrVoid;
import org.acplt.oncrpc.server.*;

/** 同进程 RemoteTea 测试服务，只持有由 SDK 管理的监听资源。 */
final class OncRpcSdkServer implements AutoCloseable {
    private final OncRpcServerTransport transport;

    private OncRpcSdkServer(OncRpcServerTransport transport) {
        this.transport = transport;
    }

    static OncRpcSdkServer tcp() throws IOException, OncRpcException {
        InetAddress host = InetAddress.getByName("127.0.0.1");
        OncRpcDispatchable dispatcher = OncRpcSdkServer::dispatch;
        OncRpcServerTransportRegistrationInfo definition = new OncRpcServerTransportRegistrationInfo(0x31234567, 1);
        OncRpcServerTransportRegistrationInfo[] contract = { definition };
        OncRpcTcpServerTransport transport = new OncRpcTcpServerTransport(dispatcher, host, 0, contract, 8192);
        transport.setCharacterEncoding(StandardCharsets.US_ASCII.name());
        transport.listen();
        return new OncRpcSdkServer(transport);
    }

    static OncRpcSdkServer udp() throws IOException, OncRpcException {
        InetAddress host = InetAddress.getByName("127.0.0.1");
        OncRpcDispatchable dispatcher = OncRpcSdkServer::dispatch;
        OncRpcServerTransportRegistrationInfo definition = new OncRpcServerTransportRegistrationInfo(0x31234567, 1);
        OncRpcServerTransportRegistrationInfo[] contract = { definition };
        OncRpcUdpServerTransport transport = new OncRpcUdpServerTransport(dispatcher, host, 0, contract, 8192);
        transport.setCharacterEncoding(StandardCharsets.US_ASCII.name());
        transport.listen();
        return new OncRpcSdkServer(transport);
    }

    private static void dispatch(OncRpcCallInformation call, int program, int version, int procedure) //
            throws OncRpcException, IOException {
        // 对应本地 interop.x，SDK 解析头部、读取 XDR 参数并编码网络回复。
        if (program != 0x31234567) {
            call.failProgramUnavailable();
        } else if (version != 1) {
            call.failProgramMismatch(1, 1);
        } else if (procedure == 0) {
            call.retrieveCall(XdrVoid.XDR_VOID);
            call.reply(XdrVoid.XDR_VOID);
        } else if (procedure == 1) {
            XdrString input = new XdrString();
            call.retrieveCall(input);
            call.reply(new XdrString("remotetea-sdk:" + input.stringValue()));
        } else {
            call.failProcedureUnavailable();
        }
    }

    int getPort() {
        return this.transport.getPort();
    }

    @Override
    public void close() {
        // 不调用 register/unregister，不访问或改动系统 rpcbind。
        this.transport.close();
    }
}
