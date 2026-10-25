/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectDemoServiceImpl;
import net.hasor.example.protocol.connect.ConnectMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Connect 与现有 gRPC 通过不同上下文共享 HTTP 监听，保持协议与服务选择隔离。 */
public class ConnectCompositionScenarioTest {
    @Test(timeout = 20000)
    public void connectAndGrpcUseSeparateContextsOnOneHttpPort() throws Exception {
        // 一个 HTTP 端点挂载两个真实协议，分别声明各自上下文。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("connect");
        ConnectorSettings endpoint = incoming.connector("http").bind("127.0.0.1", 0);
        endpoint.protocol("connect").contextPath("/connect");
        endpoint.protocol("grpc").contextPath("/grpc");
        MetConfiguration connect = new MetConfiguration().setDefaultProtocol("connect");
        connect.connector("http").protocol("connect").contextPath("/connect");
        MetConfiguration grpc = new MetConfiguration().setDefaultProtocol("grpc");
        grpc.connector("http").option("httpVersion", 2).protocol("grpc").contextPath("/grpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext connectClient = connect.buildContext(); //
             MetContext grpcClient = grpc.buildContext()) {
            // 两端启动以后发布业务服务，服务别名与协议暴露范围保持明确。
            server.start();
            connectClient.start();
            grpcClient.start();
            server.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .aliasName("grpc", "meteor.connect.demo.ConnectDemoService") //
                    .protocol("connect", "grpc").serialize("Json").register();
            MetBindInfo<ConnectDemoService> connectService = connectClient.publisher() //
                    .rsfService(ConnectDemoService.class) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                    .protocol("connect").serialize("Json").register();
            MetBindInfo<ConnectDemoService> grpcService = grpcClient.publisher() //
                    .rsfService(ConnectDemoService.class) //
                    .aliasName("grpc", "meteor.connect.demo.ConnectDemoService") //
                    .protocol("grpc").serialize("Json").register();

            // 两个公开地址复用实际端口，各自调用仍经过自己的协议适配与上下文。
            int port = server.getListenAddress("connect").getPort();
            assertEquals(port, server.getListenAddress("grpc").getPort());
            ConnectDemoService connectRemote = connectClient.getRsfClient(server.getListenAddress("connect")) //
                    .getRemote(connectService);
            ConnectDemoService grpcRemote = grpcClient.getRsfClient(server.getListenAddress("grpc")) //
                    .getRemote(grpcService);
            ConnectMessage connectResult = connectRemote.echo(ConnectMessage.of("Connect", 7));
            ConnectMessage grpcResult = grpcRemote.echo(ConnectMessage.of("gRPC", 11));
            assertEquals("Connect", connectResult.getText());
            assertEquals(8, connectResult.getNumber());
            assertEquals("gRPC", grpcResult.getText());
            assertEquals(12, grpcResult.getNumber());
        }
    }
}
