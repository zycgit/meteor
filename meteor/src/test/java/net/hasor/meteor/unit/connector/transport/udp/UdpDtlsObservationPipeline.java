/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.transport.udp;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportPipeline;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.ssl.SslHandshakeEvent;
import net.hasor.neta.codec.ssl.SslHandshakePhaseEnum;

/** Test-only observer installed after the production UDP DTLS pipeline. */
public final class UdpDtlsObservationPipeline implements TransportPipeline {
    private static final Map<String, LinkedBlockingQueue<String>>  OBSERVATIONS = new ConcurrentHashMap<>();
    private static final Map<String, LinkedBlockingQueue<Boolean>> CLOSES       = new ConcurrentHashMap<>();

    static void reset(String id) {
        OBSERVATIONS.put(id, new LinkedBlockingQueue<>());
        CLOSES.put(id, new LinkedBlockingQueue<>());
    }

    static String await(String id) throws InterruptedException {
        LinkedBlockingQueue<String> queue = OBSERVATIONS.get(id);
        return queue == null ? null : queue.poll(3, TimeUnit.SECONDS);
    }

    static Boolean awaitClose(String id) throws InterruptedException {
        LinkedBlockingQueue<Boolean> queue = CLOSES.get(id);
        return queue == null ? null : queue.poll(3, TimeUnit.SECONDS);
    }

    @Override
    public void initialize(TransportConfig config, ProtoBuildContext stack) {
        String id = config.option("dtlsObservation", "");
        if (StringUtils.isBlank(id)) {
            return;
        }
        stack.addLastDecoder("dtls-observation", new ObservationHandler(id));
    }

    private record ObservationHandler(String id) implements ProtoHandler<ByteBuf, ByteBuf> {

        @Override
            public boolean onEvent(ProtoContext context, SoEvent event) {
                if (event.getData() instanceof SslHandshakeEvent handshake) {
                    boolean completed = handshake.getPhase() == SslHandshakePhaseEnum.COMPLETE;
                    if (completed && handshake.isInitial()) {
                        String protocol = handshake.getContext().getProtocol();
                        OBSERVATIONS.computeIfAbsent(this.id, key -> new LinkedBlockingQueue<>()).add(protocol);
                    }
                }
                return true;
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, //
                    ProtoRcvQueue<ByteBuf> source, //
                    ProtoSndQueue<ByteBuf> target) {
                while (source.hasMore() && target.hasSlot()) {
                    target.offerMessage(source.takeMessage());
                }
                return ProtoStatus.Next;
            }

            @Override
            public void onClose(ProtoContext context) {
                CLOSES.computeIfAbsent(this.id, key -> new LinkedBlockingQueue<>()).add(Boolean.TRUE);
            }
        }
}
