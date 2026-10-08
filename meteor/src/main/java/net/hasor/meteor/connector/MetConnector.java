/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.List;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;

/** Lifecycle and outbound logical session acquisition. */
public interface MetConnector extends AutoCloseable {
    ConnectorConfig config();

    /** Initialize shared resources once, without listening or connecting to a peer. */
    void init() throws Exception;

    /** Disable further binds, fail pending binds and close all listeners. Existing connections remain usable.
     * Idempotent; call from the lifecycle thread before draining connections.
     */
    void closeBind();

    /** 从生命周期线程关闭监听，排空已提交写入，再关闭连接并释放资源。 */
    void close();

    /** Bind the configured endpoint. Concurrent calls share the pending operation or active listener. */
    Future<MetListen> bind();

    /** Active endpoint address, or null when it is not listening. */
    InterAddress getBindAddress();

    /** Immutable snapshot of listener handles owned by this connector, including pending binds.
     * Inspect isActive() before using a bound address; outbound-only connectors have an empty list.
     */
    List<MetListen> getListenList();

    /** Acquire a protocol session selected by the target scheme, without requiring a local bind.
     * The connector owns incoming/outgoing sessions and their physical connections.
     */
    Future<MetChannel> connect(InterAddress target);

}
