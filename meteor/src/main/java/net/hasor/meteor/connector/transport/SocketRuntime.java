/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.neta.channel.*;

/**
 * Owns Neta resources and ordered protocol executors. No RSF protocol knowledge.
 */
public final class SocketRuntime {
    private static final ThreadLocal<SocketRuntime> CURRENT    = new ThreadLocal<>();
    private final        TransportConfig            config;
    private final        TransportPipeline          pipeline;
    private              SslPipeline                security;
    private final        NetManager                 network;
    private final        ExecutorService[]          workers;
    private final        AtomicInteger              nextWorker = new AtomicInteger();
    private final        SoConfig                   socketConfig;

    SocketRuntime(TransportConfig config, TransportPipeline pipeline, ClassLoader loader, SoConfig socketConfig) {
        this.config = config;
        this.pipeline = pipeline;
        int count = config.integer("workerThread", 2);

        NetConfig settings = new NetConfig();
        settings.setClassLoader(loader);
        settings.setIoThreads(count);
        settings.setTaskThreads(config.integer("taskThread", 2));
        settings.setPrintLog(false);

        this.network = new NetManager(settings);
        this.socketConfig = socketConfig;
        this.workers = new ExecutorService[count];
        for (int i = 0; i < count; i++) {
            String name = "Meteor(" + config.name() + ")-Transport-" + i;
            this.workers[i] = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(() -> {
                    CURRENT.set(this);
                    try {
                        task.run();
                    } finally {
                        CURRENT.remove();
                    }
                }, name);
                thread.setContextClassLoader(loader);
                return thread;
            });
        }
    }

    public Executor nextExecutor() {
        return this.workers[Math.floorMod(this.nextWorker.getAndIncrement(), this.workers.length)];
    }

    /** Returns whether the caller is executing transport or ordered protocol work. */
    public static boolean isCurrentThreadInTransport() {
        boolean orderedProtocol = CURRENT.get() != null;
        return orderedProtocol || NetChannel.isCurrentThreadInPipeline() || NetChannel.isCurrentThreadInIoThread();
    }

    void installSecurity(SslPipeline security) {
        this.security = security;
    }

    NetListen bind(InterAddress address, ProtoInitializer initializer) throws IOException {
        return this.network.bind(address.toSocketAddress(), stack -> {
            this.initializePipeline(stack, initializer, null);
        }, this.socketConfig);
    }

    public Future<NetChannel> connect(InterAddress address, ProtoInitializer initializer) {
        try {
            return this.network.connectAsync(address.toSocketAddress(), stack -> {
                this.initializePipeline(stack, initializer, address.getHost());
            }, this.socketConfig);
        } catch (IOException failure) {
            BasicFuture<NetChannel> result = new BasicFuture<>();
            result.failed(failure);
            return result;
        }
    }

    private void initializePipeline(ProtoBuildContext stack, ProtoInitializer application, String peerHost) {
        try {
            if (this.security != null) {
                this.security.initialize(stack, peerHost);
            }
            if (this.pipeline != null) {
                this.pipeline.initialize(this.config, stack);
            }

            application.config(stack);
        } catch (Exception error) {
            throw ExceptionUtils.toRuntime(error);
        }
    }

    void close() {
        if (isCurrentThreadInTransport()) {
            throw new IllegalStateException("Close the connector from its lifecycle thread");
        }

        try {
            this.network.shutdown();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            for (ExecutorService worker : this.workers) {
                worker.shutdown();
            }

            for (ExecutorService worker : this.workers) {
                try {
                    worker.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
