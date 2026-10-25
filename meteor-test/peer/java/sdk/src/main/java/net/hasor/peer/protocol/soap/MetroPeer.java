/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.soap;
import java.nio.file.Path;

/** 官方 Metro 客户端与服务端入口，运行期不加载 Meteor。 */
public final class MetroPeer {
    private MetroPeer() {
    }

    public static void main(String[] arguments) throws Exception {
        SoapPeerFiles.requireIsolatedClasspath();
        switch (arguments[0]) {
            case "server": {
                try (MetroPeerServer server = new MetroPeerServer(arguments[1], arguments[2], arguments[3])) {
                    System.out.println("READY " + server.getPort());
                    System.out.flush();
                    while (System.in.read() != -1) {
                        // 宿主关闭 stdin 后，服务端释放自己创建的监听和 Endpoint。
                    }
                }
                break;
            }
            case "client": {
                Path input = Path.of(arguments[1]);
                Path output = Path.of(arguments[2]);
                SoapPeerFiles.write(output, MetroPeerClient.invoke(SoapPeerFiles.read(input)));
                break;
            }
            default: {
                throw new IllegalArgumentException("Unknown Metro peer operation: " + arguments[0]);
            }
        }
    }
}
