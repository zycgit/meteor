/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.domain.payload.Payload;

/** One protocol session. Lifecycle, receive and send run on its ordered network executor. */
public interface ProtocolSession<M> extends ChannelListener<M> {
    Future<Void> ready();

    /**
     * 将核心负载映射并发送到当前协议。消息提交完成通过接收回调映射为内部 Accept 负载。
     * 有回执的协议等待远端入队确认，HTTP 可以使用空正文的 202 或 204；原生无回执的协议在完整写出后通知，
     * 不向线上补发确认报文，也不保证远端已经执行。发送失败须传播原始失败，不能映射为 Accept。
     */
    Future<Void> send(Payload payload);

    /** Resolve pending protocol exchanges before submitted writes are drained. */
    void prepareDrain();
}
