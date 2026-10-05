/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport;

/**
 * Notifications run in channel order, outside the network engine pipeline.
 */
public interface ChannelListener<M> {
    void connected();

    void receive(M message) throws Exception;

    void closed(Throwable cause);
}
