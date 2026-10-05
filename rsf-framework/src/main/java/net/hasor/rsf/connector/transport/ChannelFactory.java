/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport;

/**
 * Attaches one receiver before a channel can deliver connected or message notifications.
 */
public interface ChannelFactory<M> {
    ChannelListener<M> create(NetworkChannel<M> channel) throws Exception;
}
