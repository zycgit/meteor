/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetContextEvent;

/** Thin test access to the synchronous service-event boundary. */
public final class ConnectorManagerTestAccess extends ConnectorManager {
    public ConnectorManagerTestAccess(MetContext context) {
        super(context);
    }

    public void apply(MetContextEvent event) {
        this.serviceEvent(event);
    }
}
