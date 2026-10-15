/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Map;

/** Runtime routing contract consumed by transports after startup planning has completed. */
public interface RouteTable<M> {
    /** Return immutable protocol entries indexed by their runtime mount ID. */
    Map<String, RouteEntry<M>> entries();

    /** Return whether the supplied runtime mount ID belongs to this endpoint. */
    boolean contains(String mountId);

    /** Select an HTTP-style route from its request URI. */
    RouteSelection select(String uri);

    /** Validate an explicit mount or resolve the only mount when no selection was supplied. */
    String fixedMount(String mountId);
}
