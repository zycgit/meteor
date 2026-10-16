/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.util.concurrent.*;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.protocol.grpc.codec.GrpcHttpCodec;

/** One protocol mount's bounded blocking-worker runtime, retained by its active sessions. */
final class GrpcMountRuntime {
    private final ProtocolConfig        config;
    private final GrpcClientRetryPolicy policy;
    private final int                   messageLimit;
    private       GrpcHttpCodec         codec;
    private       ExecutorService       workers;
    private       int                   sessions;

    GrpcMountRuntime(ProtocolConfig config) {
        this.config = config;
        this.policy = new GrpcClientRetryPolicy(config);
        this.messageLimit = config.integer("maxMessageSize", 4 * 1024 * 1024);
    }

    synchronized GrpcMountRuntime retain(MetContext context) {
        if (this.sessions == 0) {
            this.codec = new GrpcHttpCodec(this.config, context);
            this.workers = this.createWorkers(context.getClassLoader());
        }
        this.sessions++;
        return this;
    }

    private ExecutorService createWorkers(ClassLoader loader) {
        int maximum = this.config.integer("streamWorkers", 64);
        SynchronousQueue<Runnable> workQueue = new SynchronousQueue<>();
        return new ThreadPoolExecutor(0, maximum, 30, TimeUnit.SECONDS, workQueue, task -> {
            Thread thread = new Thread(task, "Meteor-gRPC-Flow-" + this.config.mountId());
            thread.setContextClassLoader(loader);
            thread.setDaemon(true);
            return thread;
        });
    }

    GrpcClientRetryPolicy policy() {
        return this.policy;
    }

    int messageLimit() {
        return this.messageLimit;
    }

    GrpcHttpCodec codec() {
        return this.codec;
    }

    Executor workers() {
        return this.workers;
    }

    synchronized void release() {
        if (this.sessions == 0) {
            return;
        }
        this.sessions--;
        if (this.sessions == 0) {
            this.workers.shutdownNow();
            this.workers = null;
            this.codec = null;
        }
    }
}
