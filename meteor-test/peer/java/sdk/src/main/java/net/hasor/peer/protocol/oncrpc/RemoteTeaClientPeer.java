/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.oncrpc;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.acplt.oncrpc.OncRpcClient;
import org.acplt.oncrpc.OncRpcException;
import org.acplt.oncrpc.OncRpcTcpClient;
import org.acplt.oncrpc.OncRpcUdpClient;
import org.acplt.oncrpc.XdrString;

/** 独立 RemoteTea Java 客户端；使用 SDK 的完整 RPC 与 XDR 实现，不加载 Meteor。 */
public final class RemoteTeaClientPeer {
    private RemoteTeaClientPeer() {
    }

    public static void main(String[] arguments) throws Exception {
        // RemoteTea 是独立 Java 实现，不将它误称为 ONC RPC 标准的官方 SDK。
        requireIsolatedClasspath();
        InetAddress host = InetAddress.getByName(arguments[1]);
        int port = Integer.parseInt(arguments[2]);
        boolean expectRejection = switch (arguments[4]) {
            case "echo" -> false;
            case "reject-non-ascii" -> true;
            default -> throw new IllegalArgumentException("Unknown scenario: " + arguments[4]);
        };
        OncRpcClient client = switch (arguments[0]) {
            case "tcp" -> new OncRpcTcpClient(host, 0x31234567, 1, port);
            case "udp" -> new OncRpcUdpClient(host, 0x31234567, 1, port);
            default -> throw new IllegalArgumentException("Unknown transport: " + arguments[0]);
        };
        try {
            // 程序号、版本号和过程号对应 interop.x；显式端口不依赖本机 rpcbind。
            client.setTimeout(5000);
            client.setCharacterEncoding(StandardCharsets.UTF_8.name());
            XdrString input = new XdrString(arguments[3]);
            XdrString output = new XdrString();
            try {
                client.call(1, input, output);
            } catch (OncRpcException failure) {
                // 非 ASCII 参数必须由远端明确拒绝，超时或本地编码异常不能冒充 GARBAGE_ARGS。
                if (!expectRejection || failure.getReason() != OncRpcException.RPC_CANTDECODEARGS) {
                    throw failure;
                }
                System.out.println("REJECTED RPC_CANTDECODEARGS");
                return;
            }
            if (expectRejection) {
                throw new AssertionError("Meteor accepted non-ASCII input for an ASCII XDR string");
            }

            // 进程控制结果编码不参与 RPC，测试端同时校验退出码和远端返回值。
            byte[] bytes = output.stringValue().getBytes(StandardCharsets.UTF_8);
            System.out.println("RESULT " + Base64.getEncoder().encodeToString(bytes));
        } finally {
            client.close();
        }
    }

    private static void requireIsolatedClasspath() throws ClassNotFoundException {
        try {
            Class.forName("net.hasor.meteor.MetContext", false, RemoteTeaClientPeer.class.getClassLoader());
        } catch (ClassNotFoundException expected) {
            return;
        }
        throw new IllegalStateException("RemoteTea peer must not load Meteor runtime");
    }
}
