/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.ProtocolTestAccess;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpUpgradeTestAccess;
import net.hasor.meteor.connector.transport.http.websocket.WebSocketPayload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.scenario.rpc.websocket.example.JsonWebSocketProtocol;
import org.junit.Test;
import static org.junit.Assert.*;

/** test-only 协议的 SPI 单元验证；失败写出不能向 Caller 提交本地成功。 */
public class WebSocketNotificationWriteTest {
    @Test
    public void failedPhysicalWriteDoesNotProduceAnAccept() throws Exception {
        // 只替换物理发送 Future；使用真实示例 ProtocolFactory 和正式 JSON 编码器。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            ControlledConnection connection = new ControlledConnection();
            ProtocolConfig config = ProtocolTestAccess.config("jsonws", "jsonws", "jsonws", //
                    Map.of("contextPath", "/ws"));
            ProtocolSessionContext protocolContext = ProtocolTestAccess.context(context, null, null, true);
            AtomicInteger delivered = new AtomicInteger();
            JsonWebSocketProtocol protocol = new JsonWebSocketProtocol();
            ProtocolSession<HttpExchange> session = protocol.createSession(config, protocolContext, //
                    connection, (payload, id) -> delivered.incrementAndGet());
            session.connected();
            assertTrue(session.ready().isDone());
            RequestPayload request = new RequestPayload();
            request.setRequestID(1);
            request.setMessage(true);
            request.setSerializeType("Json");

            // 写出仍待定时没有 Accept，失败后也不能将通知误报为已提交。
            Future<Void> sent = session.send(request);
            assertFalse(sent.isDone());
            assertEquals(0, delivered.get());
            IOException failure = new IOException("Physical message write failed");
            connection.physical.failed(failure);
            assertSame(failure, sent.getCause());
            assertEquals(0, delivered.get());
            session.closed(failure);
        }
    }

    private static final class ControlledConnection implements NetworkChannel<HttpExchange> {
        private final BasicFuture<Void> physical = new BasicFuture<>();
        private final BasicFuture<Void> closed   = new BasicFuture<>();

        @Override
        public InterAddress getLocal() {
            return null;
        }

        @Override
        public InterAddress getRemote() {
            return null;
        }

        @Override
        public boolean isOpen() {
            return !this.closed.isDone();
        }

        @Override
        public Future<Void> write(HttpExchange exchange) {
            HttpUpgradeTestAccess.attach(exchange, WebSocketPayload.class, Runnable::run, //
                    packet -> this.physical, () -> {
                    });
            BasicFuture<Void> sent = new BasicFuture<>();
            sent.completed(null);
            return sent;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public Future<Void> close() {
            this.closed.completed(null);
            return this.closed;
        }

        @Override
        public Future<Void> drainAndClose() {
            return this.close();
        }
    }
}
