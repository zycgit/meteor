/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** 验证传输通道创建期间的监听器延迟绑定与关闭终态。 */
public class ChannelListenerRelayTest {
    @Test
    public void closeBeforeListenerAttachmentIsDeliveredInOrder() {
        // 在监听器创建完成前记录物理关闭，暂不宣告生命周期结束。
        List<String> events = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("closed while attaching");
        ChannelListenerRelay<byte[]> relay = new ChannelListenerRelay<>(() -> events.add("terminated"));
        relay.closed(failure);
        assertEquals(List.of(), events);

        // 监听器绑定后先收到原始关闭原因，随后才释放通道生命周期。
        relay.attach(new ChannelListener<byte[]>() {
            @Override
            public void connected() {
                events.add("connected");
            }

            @Override
            public void receive(byte[] message) {
                events.add("received");
            }

            @Override
            public void closed(Throwable cause) {
                assertSame(failure, cause);
                events.add("closed");
            }
        });
        assertEquals(List.of("closed", "terminated"), events);
    }

    @Test
    public void listenerConstructionFailureStillReleasesClosedChannel() {
        // 监听器工厂失败时没有可通知对象，物理关闭直接释放通道生命周期。
        AtomicInteger terminations = new AtomicInteger();
        ChannelListenerRelay<byte[]> relay = new ChannelListenerRelay<>(terminations::incrementAndGet);
        relay.attachmentFailed();
        relay.closed(new IllegalStateException("listener failed"));

        // 重复关闭不能重复释放同一通道。
        relay.closed(new IllegalStateException("duplicate close"));
        assertEquals(1, terminations.get());
    }

    @Test
    public void connectedBeforeListenerAttachmentIsReplayedOnce() {
        // 极端调度下 active 事件先到达，生命周期先保存连接状态。
        AtomicInteger connected = new AtomicInteger();
        ChannelListenerRelay<byte[]> relay = new ChannelListenerRelay<>(() -> {
        });
        relay.connected();

        // 监听器完成绑定后补发一次 connected，重复 active 不再传播。
        relay.attach(new ChannelListener<byte[]>() {
            @Override
            public void connected() {
                connected.incrementAndGet();
            }

            @Override
            public void receive(byte[] message) {
            }

            @Override
            public void closed(Throwable cause) {
            }
        });
        relay.connected();
        assertEquals(1, connected.get());
    }
}
