/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport;
import java.util.function.Function;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.ProtoInitializer;

/** Declares protocol branches before network activation; receives selected application events. */
public interface RoutedReceiver<M> extends ChannelListener<M> {
    void configure(ProtoBuildContext context, Function<String, ProtoInitializer> branch);

    void receive(String route, M message) throws Exception;
}
