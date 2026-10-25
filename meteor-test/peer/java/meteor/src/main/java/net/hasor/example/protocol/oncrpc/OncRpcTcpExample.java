/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.oncrpc;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;

/** 通过公开启动、发布、订阅 API 执行 ONC RPC TCP 调用。 */
public final class OncRpcTcpExample {
    private OncRpcTcpExample() {
    }

    public static void main(String[] args) throws Exception {
        // 选择 TCP 和 ONC RPC；监听端口 0 由操作系统分配。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("oncrpc");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("oncrpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("oncrpc");
        outgoing.connector("tcp").protocol("oncrpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布 Java 服务，再订阅同一程序和版本。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 普通接口代理走统一 Caller/Dispatcher，不直接调用编解码器。
            OncRpcDemoService remote = client.getRsfClient(server.getListenAddress("oncrpc")).getRemote(service);
            System.out.println(remote.echo("Hello ONC RPC TCP"));
            System.out.println(remote.add(19, 23));
        }
    }
}
