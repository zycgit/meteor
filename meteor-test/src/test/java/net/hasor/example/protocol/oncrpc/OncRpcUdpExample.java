/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.oncrpc;
import java.net.DatagramSocket;
import java.net.InetAddress;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;

/** 通过公开启动、发布、订阅 API 执行 ONC RPC UDP 调用。 */
public final class OncRpcUdpExample {
    private OncRpcUdpExample() {
    }

    public static void main(String[] args) throws Exception {
        // 选择传输和协议；UDP 要配置具体监听端口。
        int port;
        try (DatagramSocket reservation = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            port = reservation.getLocalPort();
        }
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        incoming.connector("udp").bind("127.0.0.1", port).protocol("oncrpc-udp");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        outgoing.connector("udp").protocol("oncrpc-udp");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布 Java 服务，再订阅同一程序和版本。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 普通接口代理走统一 Caller/Dispatcher，不直接调用编解码器。
            OncRpcDemoService remote = client.getRsfClient(server.getListenAddress("oncrpc-udp")).getRemote(service);
            System.out.println(remote.echo("Hello ONC RPC UDP"));
            System.out.println(remote.add(19, 23));
        }
    }
}
