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
import net.hasor.cobble.ClassUtils;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;

/**
 * Owns Neta resources and ordered protocol executors. No RSF protocol knowledge.
 */
public final class SocketTransport implements AutoCloseable {
    private final        ConnectorConfig              config;
    private final        TransportPipeline            pipeline;
    private static final ThreadLocal<SocketTransport> CURRENT    = new ThreadLocal<>();
    private final        NetManager                   network;
    private final        ExecutorService[]            workers;
    private final        AtomicInteger                nextWorker = new AtomicInteger();
    private final        SoConfig                     socketConfig;

    public SocketTransport(ConnectorConfig config, ClassLoader loader, SoConfig socketConfig) {
        this.config = config;
        this.pipeline = this.loadPipeline(config, loader);
        int count = config.integer("workerThread", 2);
        if (count <= 0) {
            throw new IllegalArgumentException("workerThread must be positive");
        }

        NetConfig settings = new NetConfig();
        settings.setClassLoader(loader);
        settings.setIoThreads(count);
        settings.setTaskThreads(config.integer("taskThread", 2));
        settings.setPrintLog(false);

        this.network = new NetManager(settings);
        this.socketConfig = socketConfig;
        this.workers = new ExecutorService[count];
        for (int i = 0; i < count; i++) {
            String name = "RSF(" + config.name() + ")-Protocol-" + i;
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

    public NetListen bind(InterAddress address, ProtoInitializer initializer) throws IOException {
        return this.network.bind(address.toSocketAddress(), stack -> {
            this.initializePipeline(stack, initializer);
        }, this.socketConfig);
    }

    public Future<NetChannel> connect(InterAddress address, ProtoInitializer initializer) {
        try {
            return this.network.connectAsync(address.toSocketAddress(), stack -> {
                this.initializePipeline(stack, initializer);
            }, this.socketConfig);
        } catch (IOException failure) {
            BasicFuture<NetChannel> result = new BasicFuture<>();
            result.failed(failure);
            return result;
        }
    }

    private TransportPipeline loadPipeline(ConnectorConfig config, ClassLoader loader) {
        String name = config.option("transportPipeline", "");
        if (name.isEmpty()) {
            return null;
        }

        try {
            return ClassUtils.newInstance(ClassUtils.getClass(loader, name).asSubclass(TransportPipeline.class));
        } catch (ClassNotFoundException failure) {
            throw new IllegalArgumentException("Unknown transport pipeline: " + name, failure);
        }
    }

    private void initializePipeline(ProtoBuildContext stack, ProtoInitializer application) {
        try {
            if (this.pipeline != null) {
                this.pipeline.initialize(this.config, stack);
            }

            application.config(stack);
        } catch (Exception error) {
            throw ExceptionUtils.toRuntime(error);
        }
    }

    @Override
    public void close() {
        if (CURRENT.get() == this || NetChannel.isCurrentThreadInPipeline()) {
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