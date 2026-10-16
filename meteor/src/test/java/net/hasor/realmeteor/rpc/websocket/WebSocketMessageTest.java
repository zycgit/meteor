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
import net.hasor.example.rpc.websocket.WebSocketObservation;
import net.hasor.example.rpc.websocket.WebSocketSample;
import net.hasor.example.rpc.websocket.WebSocketSampleService;
import org.junit.Test;
import static org.junit.Assert.*;

/** 无回执消息的 Caller 完成只确认协议写出，不承诺远端业务完成。 */
public class WebSocketMessageTest {
    @Test(timeout = 30000)
    public void notificationCompletesAfterWriteWithoutAResponseFrame() throws Exception {
        // 两侧通过扩展观察真实应用消息，业务方法保持阻塞。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        incoming.addExtension(context -> new WebSocketObservation());
        outgoing.addExtension(context -> new WebSocketObservation());
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 1).bind("127.0.0.1", 0);
        WebSocketConfigurations.websocket(listener, 1, false);
        WebSocketConfigurations.websocket(WebSocketConfigurations.http(outgoing, 1), 1, false);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();
            WebSocketObservation sent = client.getExtension(WebSocketObservation.class);
            WebSocketObservation received = server.getExtension(WebSocketObservation.class);
            MetClient remote = client.getRsfClient(server.bindAddress("jsonws"));

            // 写出后 Caller 已完成，服务端仍在业务闩锁内，线上没有回执消息。
            try {
                MetFuture notification = remote.asyncInvoke(service, "notifyBlocked", new Class<?>[0], new Object[0]);
                assertNull(notification.get(5, TimeUnit.SECONDS).getData());
                assertTrue(notification.getRequest().isMessage());
                assertTrue(implementation.entered().await(5, TimeUnit.SECONDS));
                received.suppressedAccept().get(5, TimeUnit.SECONDS);
                assertEquals(1L, implementation.completed().getCount());
                assertEquals(1, sent.sentRequests());
                assertEquals(1, received.receivedRequests());
                assertEquals(0, received.sentResponses());
                assertEquals(0, sent.receivedResponses());

                // 同一会话上的普通调用仍等待真正响应，通知不会污染响应关联。
                assertEquals("server:after-notification", remote.getRemote(service).echo("after-notification"));
                assertEquals(2, received.receivedRequests());
                assertEquals(1, sent.receivedResponses());
            } finally {
                implementation.release();
            }
            assertTrue(implementation.completed().await(5, TimeUnit.SECONDS));
        }
    }
}
