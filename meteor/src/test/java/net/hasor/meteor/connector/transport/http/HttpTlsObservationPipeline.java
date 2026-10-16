/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLPeerUnverifiedException;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportPipeline;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.ssl.SslContext;
import net.hasor.neta.codec.ssl.SslHandshakeEvent;
import net.hasor.neta.codec.ssl.SslHandshakePhaseEnum;

/** Test-only observer installed after the production TLS transport pipeline. */
public final class HttpTlsObservationPipeline implements TransportPipeline {
    private static final Map<String, LinkedBlockingQueue<Observation>> OBSERVATIONS = new ConcurrentHashMap<>();

    static void reset(String id) {
        OBSERVATIONS.put(id, new LinkedBlockingQueue<>());
    }

    static Observation await(String id) throws InterruptedException {
        LinkedBlockingQueue<Observation> queue = OBSERVATIONS.get(id);
        return queue == null ? null : queue.poll(3, TimeUnit.SECONDS);
    }

    @Override
    public void initialize(TransportConfig config, ProtoBuildContext stack) {
        String id = config.option("tlsObservation", "");
        if (StringUtils.isBlank(id)) {
            return;
        }

        stack.addLastDecoder("tls-observation", new ObservationHandler(id));
    }

    private static void observe(String id, SoEvent event) {
        if (event.getData() instanceof SslHandshakeEvent handshake) {
            if (handshake.getPhase() != SslHandshakePhaseEnum.COMPLETE || !handshake.isInitial()) {
                return;
            }

            // 只记录实际完成的会话，配置值不能冒充协商结果或已使用的身份。
            SslContext context = handshake.getContext();
            Certificate[] local = context.getLocalCertificates();
            Certificate[] peer;
            try {
                peer = context.getPeerCertificates();
            } catch (SSLPeerUnverifiedException ignored) {
                peer = new Certificate[0];
            }

            Observation observation = new Observation(context.getProtocol(), context.getApplicationProtocol(), //
                    subject(local), subject(peer));
            OBSERVATIONS.computeIfAbsent(id, key -> new LinkedBlockingQueue<>()).add(observation);
        }
    }

    private static String subject(Certificate[] chain) {
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate certificate)) {
            return null;
        }
        return certificate.getSubjectX500Principal().getName();
    }

    static final class Observation {
        private final String tlsProtocol;
        private final String applicationProtocol;
        private final String localSubject;
        private final String peerSubject;

        private Observation(String tlsProtocol, String applicationProtocol, String localSubject, String peerSubject) {
            this.tlsProtocol = tlsProtocol;
            this.applicationProtocol = applicationProtocol;
            this.localSubject = localSubject;
            this.peerSubject = peerSubject;
        }

        String tlsProtocol() {
            return this.tlsProtocol;
        }

        String applicationProtocol() {
            return this.applicationProtocol;
        }

        String localSubject() {
            return this.localSubject;
        }

        String peerSubject() {
            return this.peerSubject;
        }
    }

    private static final class ObservationHandler implements ProtoHandler<ByteBuf, ByteBuf> {
        private final String id;

        private ObservationHandler(String id) {
            this.id = id;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            observe(this.id, event);
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
    }
}
