/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.support.connector.protocol.SocketTestSupport;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过公开管理器、传输 SPI 和协议 SPI 验证消息订阅边界。 */
public class ConnectorSubscriptionsTest {
    @Test(timeout = 10000)
    public void inboundPayloadIsDeliveredInRegistrationOrder() throws Exception {
        // 启动真实 TCP 端点，并按顺序注册两个统一消息订阅者。
        ConnectorConfig config = this.config();
        try (ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();
            List<String> deliveries = new CopyOnWriteArrayList<>();
            AtomicReference<Delivery> first = new AtomicReference<>();
            CountDownLatch delivered = new CountDownLatch(2);
            manager.subscribe((channel, requestId, payload) -> {
                first.set(new Delivery(channel, requestId, payload));
                deliveries.add("first");
                delivered.countDown();
            });
            manager.subscribe((channel, requestId, payload) -> {
                deliveries.add("second");
                delivered.countDown();
            });
            manager.startInbound();

            // 原始报文经传输选路和协议解析后进入统一订阅入口。
            this.send(manager.bindAddress("a"), "AArequest\n");
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("first", "second"), deliveries);

            // 回调保留协议通道、协议请求标识及解析后的负载。
            Delivery delivery = first.get();
            assertNotNull(delivery);
            assertEquals("a", delivery.channel.getProtocol());
            assertEquals(1, delivery.requestId);
            assertTrue(delivery.payload instanceof RequestPayload);
        }
    }

    @Test(timeout = 10000)
    public void subscriberFailureDoesNotPreventFollowingDelivery() throws Exception {
        // 第一个订阅者故障，第二个订阅者观察同一条真实入站消息。
        ConnectorConfig config = this.config();
        try (ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<Payload> received = new AtomicReference<>();
            manager.subscribe((channel, requestId, payload) -> {
                throw new IllegalStateException("observer failure");
            });
            manager.subscribe((channel, requestId, payload) -> {
                received.set(payload);
                delivered.countDown();
            });
            manager.startInbound();

            // 单个观察者异常不能截断后续订阅者。
            this.send(manager.bindAddress("a"), "AArequest\n");
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            assertTrue(received.get() instanceof RequestPayload);
        }
    }

    @Test(timeout = 10000)
    public void subscriberCanCloseManagerAfterSubmittingReply() throws Exception {
        // 启动单协议线程端点；订阅回调与应答写入使用同一个有序执行器。
        ConnectorConfig config = this.config();
        try (ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();
            CountDownLatch closeReturned = new CountDownLatch(1);
            AtomicReference<Future<MetChannel>> submitted = new AtomicReference<>();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            manager.subscribe((channel, requestId, payload) -> {
                try {
                    ResponsePayload response = new ResponsePayload();
                    response.setRequestID(requestId);
                    response.setReturnData("reply");
                    submitted.set(channel.sendData(response));
                    manager.close();
                } catch (Throwable failure) {
                    callbackFailure.set(failure);
                } finally {
                    closeReturned.countDown();
                }
            });
            manager.startInbound();

            // close 必须先让出协议线程，外部重复 close 则等待同一关闭屏障完成。
            InterAddress address = manager.bindAddress("a");
            try (Socket socket = new Socket(address.getHost(), address.getPort())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write("AArequest\n".getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                assertTrue(closeReturned.await(3, TimeUnit.SECONDS));
                manager.close();

                // 已在回调中受理的应答先完成，随后传输关闭并向对端交付 EOF。
                assertNull(callbackFailure.get());
                Future<MetChannel> writing = submitted.get();
                assertNotNull(writing);
                writing.get(3, TimeUnit.SECONDS);
                byte[] received = socket.getInputStream().readAllBytes();
                assertEquals("AAreply\n", new String(received, StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    public void closedManagerCannotBeReinitialized() {
        // 初始化并关闭管理器，close 是该运行时对象的终态。
        ConnectorConfig config = this.config();
        try (ConnectorManager manager = SocketTestSupport.manager(config)) {
            manager.init();
            manager.close();

            // 已释放的对象不能创建第二套定时器和网络资源。
            IllegalStateException failure = assertThrows(IllegalStateException.class, manager::init);
            assertEquals("Connector manager is closed", failure.getMessage());
        }
    }

    private ConnectorConfig config() {
        Map<String, String> options = new HashMap<>();
        options.put("workerThread", "1");
        ProtocolConfig protocol = ProtocolTestAccess.config("a", "a", "wirea", Collections.emptyMap());
        InterAddress address = InterAddress.forBinding("tcp", "127.0.0.1", 0, "test");
        return new ConnectorConfig("subscriptions", address, options, List.of(protocol), true);
    }

    private void send(InterAddress address, String text) throws Exception {
        try (Socket socket = new Socket(address.getHost(), address.getPort())) {
            socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }
    }

    private record Delivery(MetChannel channel, long requestId, Payload payload) {
    }
}
