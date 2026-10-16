/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.session;
import java.util.concurrent.*;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.protocol.connect.codec.ConnectCors;
import net.hasor.meteor.protocol.connect.codec.ConnectHttpCodec;

/** 一个协议挂载共享编解码器和有界工作池，最后一个会话退出时释放自行创建的线程。 */
public final class ConnectMountRuntime {
    private final ProtocolConfig   config;
    private final int              messageLimit;
    private final ConnectCors      cors;
    private       ConnectHttpCodec codec;
    private       ExecutorService  workers;
    private       int              sessions;

    public ConnectMountRuntime(ProtocolConfig config) {
        this.config = config;
        this.cors = new ConnectCors(config);
        this.messageLimit = config.integer("maxMessageSize", 4 * 1024 * 1024);
    }

    public synchronized ConnectMountRuntime retain(MetContext context) {
        if (this.sessions == 0) {
            this.codec = new ConnectHttpCodec(this.config, context);
            this.workers = this.createWorkers(context.getClassLoader());
        }
        this.sessions++;
        return this;
    }

    private ExecutorService createWorkers(ClassLoader loader) {
        int maximum = this.config.integer("streamWorkers", 64);
        SynchronousQueue<Runnable> queue = new SynchronousQueue<>();
        return new ThreadPoolExecutor(0, maximum, 30, TimeUnit.SECONDS, queue, task -> {
            Thread thread = new Thread(task, "Meteor-Connect-Flow-" + this.config.name());
            thread.setContextClassLoader(loader);
            thread.setDaemon(true);
            return thread;
        });
    }

    int messageLimit() {
        return this.messageLimit;
    }

    ConnectCors cors() {
        return this.cors;
    }

    ConnectHttpCodec codec() {
        return this.codec;
    }

    Executor workers() {
        return this.workers;
    }

    public synchronized void release() {
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
