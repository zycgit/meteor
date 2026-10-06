/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorContractTest {
    @Test
    public void everyListenerStopsBeforeAnyConnectorIsReleased() throws Exception {
        try (EndpointFixture fixture = new EndpointFixture("a", "b")) {
            fixture.manager.bind("a").get();
            fixture.manager.bind("b").get();
            fixture.manager.close();
            assertTrue(fixture.events.indexOf("a:unbound") < fixture.events.indexOf("a:closed"));
            assertTrue(fixture.events.indexOf("b:unbound") < fixture.events.indexOf("a:closed"));
            assertTrue(fixture.events.indexOf("a:unbound") < fixture.events.indexOf("b:closed"));
            assertTrue(fixture.events.indexOf("b:unbound") < fixture.events.indexOf("b:closed"));
        }
    }

    @Test
    public void publicContractHasNoManagerRegistrationCallbacks() throws Exception {
        assertEquals(0, MetConnector.class.getMethod("bind").getParameterCount());
        for (String name : new String[] { "onClosing", "onChannelConnected", "onChannelClosed" }) {
            for (Method method : MetConnector.class.getMethods()) {
                assertNotEquals(name, method.getName());
            }
        }
    }
}
