/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.io.IOException;
import java.util.concurrent.Executor;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoConfig;

/** Test-only access to the socket transport owned by {@link SocketConnector}. */
public final class TestSocketRuntime implements AutoCloseable {
    private final SocketRuntime transport;

    public TestSocketRuntime(TransportConfig config, ClassLoader loader, SoConfig socketConfig) {
        TransportPlan<Object> plan = TransportPlan.withPipeline(config, loader, (pipeline, services) -> {
            return new PipelineCapture(pipeline);
        });
        PipelineCapture capture = (PipelineCapture) plan.create(TransportTestServices.newRuntimeServices(loader));
        this.transport = new SocketRuntime(config, capture.pipeline, loader, socketConfig);
    }

    public Executor nextExecutor() {
        return this.transport.nextExecutor();
    }

    public NetListen bind(InterAddress address, ProtoInitializer initializer) throws IOException {
        return this.transport.bind(address, initializer);
    }

    public Future<NetChannel> connect(InterAddress address, ProtoInitializer initializer) {
        return this.transport.connect(address, initializer);
    }

    @Override
    public void close() {
        this.transport.close();
    }

    private static final class PipelineCapture implements NetworkConnector<Object> {
        private final TransportPipeline pipeline;

        private PipelineCapture(TransportPipeline pipeline) {
            this.pipeline = pipeline;
        }

        @Override
        public NetworkListen bind(InterAddress address, RouteTable<Object> routes, //
                ChannelListenerFactory<Object> factory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> connect(InterAddress address, RouteTable<Object> routes, String initialMountId, //
                ChannelListenerFactory<Object> factory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
