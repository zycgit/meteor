/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.rpc.websocket;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.example.rpc.websocket.WebSocketConfigurations;
import net.hasor.example.rpc.websocket.WebSocketSample;
import net.hasor.example.rpc.websocket.WebSocketSampleService;
import org.junit.Test;
import static org.junit.Assert.*;

/** RFC 8441 扩展 CONNECT 的真实 RPC 与单个 HTTP/2 stream 生命周期。 */
public class WebSocketHttp2BootstrapTest {
    @Test(timeout = 30000)
    public void extendedConnectCarriesRealJsonRpc() throws Exception {
        // HTTP/2 明文先验连接使用 RFC 8441 CONNECT，而不是 HTTP/1 Upgrade。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 2).bind("127.0.0.1", 0);
        WebSocketConfigurations.websocket(listener, 2, false);
        WebSocketConfigurations.websocket(WebSocketConfigurations.http(outgoing, 2), 2, false);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端使用公开发布与订阅，传输升级不绕过实际 RPC。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();

            // 只有完成 H2 升级且消息抵达 Dispatcher 后才会得到业务前缀。
            WebSocketSample remote = client.getRsfClient(server.getListenAddress("jsonws")).getRemote(service);
            assertEquals("server:h2🙂", remote.echo("h2🙂"));
            assertEquals(1, implementation.calls());
        }
    }

    @Test(timeout = 30000)
    public void closingOneWebSocketStreamLeavesTheOtherStreamUsable() throws Exception {
        // 一个 ProtocolSession 在同一个 HTTP 通道上打开两个 WS stream，轮流承载请求。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 2).bind("127.0.0.1", 0);
        WebSocketConfigurations.websocket(listener, 2, false);
        ConnectorSettings sender = WebSocketConfigurations.http(outgoing, 2);
        WebSocketConfigurations.websocket(sender, 2, false) //
                .option("streams", 2).option("closeStreamOnCancel", true);
        WebSocketSampleService first = new WebSocketSampleService();
        WebSocketSampleService second = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两个服务的闩锁独立，能够观察另一个 stream 的在途调用是否被误取消。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, first).name("first") //
                    .protocol("jsonws").serialize("Json").register();
            server.publisher().rsfService(WebSocketSample.class, second).name("second") //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> firstService = client.publisher().rsfService(WebSocketSample.class) //
                    .name("first").protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> secondService = client.publisher().rsfService(WebSocketSample.class) //
                    .name("second").protocol("jsonws").serialize("Json").register();
            MetClient remote = client.getRsfClient(server.getListenAddress("jsonws"));

            // 两个 stream 都已进入业务后，关闭第一个；第二个必须保留自己的结果。
            try {
                MetFuture cancelled = remote.asyncInvoke(firstService, "block", new Class<?>[0], new Object[0]);
                assertTrue(first.entered().await(5, TimeUnit.SECONDS));
                MetFuture surviving = remote.asyncInvoke(secondService, "block", new Class<?>[0], new Object[0]);
                assertTrue(second.entered().await(5, TimeUnit.SECONDS));
                assertTrue(cancelled.cancel());
                assertTrue(cancelled.isDone());
                assertTrue(cancelled.isCancelled());
                assertNull(cancelled.getResult());
                assertFalse(surviving.isDone());
                second.release();
                assertEquals("released", surviving.getData(5, TimeUnit.SECONDS));

                // 另一个已升级 stream 还能继续承载新的业务调用。
                WebSocketSample remoteSecond = remote.getRemote(secondService);
                assertEquals("server:surviving-stream", remoteSecond.echo("surviving-stream"));
                assertEquals(2, second.calls());
            } finally {
                first.release();
                second.release();
            }
        }
    }
}
