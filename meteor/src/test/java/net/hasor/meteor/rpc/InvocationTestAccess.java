/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.domain.payload.RequestPayload;

/** 入站单元测试所需的包内转发，实际执行仍由生产调度器和任务负责。 */
public final class InvocationTestAccess implements AutoCloseable {
    private final MetDispatcher dispatcher;

    public InvocationTestAccess(MetContext context, MetFilterProvider filters) {
        this.dispatcher = new MetDispatcher(context, filters);
    }

    public void run(MetChannel channel, RequestPayload request, long receivedNanos) {
        new MetInvocationTask(channel, this.dispatcher, request, receivedNanos).run();
    }

    public void onRequest(MetChannel channel, long requestId, RequestPayload request) {
        this.dispatcher.onRequest(channel, requestId, request);
    }

    @Override
    public void close() {
        this.dispatcher.close();
    }
}
