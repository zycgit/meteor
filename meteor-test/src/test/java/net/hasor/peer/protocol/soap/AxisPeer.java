/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.soap;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import com.sun.net.httpserver.HttpServer;

/** 官方 Axis 引擎的独立运行入口。 */
public final class AxisPeer {
    private AxisPeer() {
    }

    public static void main(String[] arguments) throws Exception {
        SoapPeerFiles.requireIsolatedClasspath();
        switch (arguments[0]) {
            case "server": {
                try (AxisRpcEncodedPeer engine = new AxisRpcEncodedPeer()) {
                    HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
                    try {
                        server.createContext("/services/RpcEchoService", engine);
                        server.start();
                        System.out.println("READY " + server.getAddress().getPort());
                        System.out.flush();
                        while (System.in.read() != -1) {
                            // 宿主拥有进程，进程拥有 HTTP 监听及 Axis 引擎。
                        }
                    } finally {
                        server.stop(0);
                    }
                }
                break;
            }
            case "client": {
                Path input = Path.of(arguments[1]);
                Path output = Path.of(arguments[2]);
                SoapPeerFiles.write(output, AxisPeerClient.invoke(SoapPeerFiles.read(input)));
                break;
            }
            default: {
                throw new IllegalArgumentException("Unknown Axis peer operation: " + arguments[0]);
            }
        }
    }
}
