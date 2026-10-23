/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.session;
import java.util.concurrent.*;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.protocol.triple.codec.TripleHttpCodec;

/** 一个协议挂载共享编解码器和有界工作池，最后一个会话退出时释放自行创建的线程。 */
public final class TripleMountRuntime {
    private final ProtocolConfig  config;
    private final RouteMountIndex routes;
    private final int             bodyLimit;
    private       TripleHttpCodec codec;
    private       ExecutorService workers;
    private       int             sessions;

    public TripleMountRuntime(ProtocolConfig config, RouteMountIndex routes) {
        this.config = config;
        this.routes = routes;
        int messageLimit = config.integer("maxMessageSize", 4 * 1024 * 1024);
        // 聚合普通 RPC 时额外保留五字节帧头；编解码器分别限制压缩前后的消息长度。
        long framedLimit = (long) messageLimit + 5;
        this.bodyLimit = (int) Math.min(Integer.MAX_VALUE - 1, framedLimit);
    }

    public synchronized TripleMountRuntime retain(MetContext context) {
        if (this.sessions == 0) {
            this.codec = new TripleHttpCodec(this.config, this.routes, context);
            this.workers = this.createWorkers(context.getClassLoader());
        }
        this.sessions++;
        return this;
    }

    private ExecutorService createWorkers(ClassLoader loader) {
        int maximum = this.config.integer("streamWorkers", 64);
        SynchronousQueue<Runnable> queue = new SynchronousQueue<>();
        return new ThreadPoolExecutor(0, maximum, 30, TimeUnit.SECONDS, queue, task -> {
            Thread thread = new Thread(task, "Meteor-Triple-Flow-" + this.config.name());
            thread.setContextClassLoader(loader);
            thread.setDaemon(true);
            return thread;
        });
    }

    int bodyLimit() {
        return this.bodyLimit;
    }

    TripleHttpCodec codec() {
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
