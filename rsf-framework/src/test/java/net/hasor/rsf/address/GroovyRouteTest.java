/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address;

import net.hasor.rsf.address.AddressPool;
import net.hasor.rsf.address.InterAddress;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class GroovyRouteTest {
    @Test public void addressComponentLoadsGroovyThroughSpiAndKeepsLastValidRule() throws Exception {
        AddressPool pool = new AddressPool();
        InterAddress first = new InterAddress("rsf://127.0.0.1:2181/default");
        InterAddress second = new InterAddress("rsf://127.0.0.1:2182/default");
        pool.appendAddress("service", Arrays.asList(first, second));
        String rule = "def evalAddress(serviceID, addresses) { return addresses.findAll { it.endsWith(':2182') } }";
        assertTrue(pool.updateServiceRoute("service", rule));
        assertEquals(second, pool.nextAddress("service", "echo", new Object[0]));
        assertFalse(pool.updateServiceRoute("service", "def broken {"));
        assertEquals(rule, pool.serviceRoute("service"));
        assertEquals(second, pool.nextAddress("service", "echo", new Object[0]));
        assertTrue(pool.updateServiceRoute("service", ""));
        assertNull(pool.serviceRoute("service"));
        pool.removeAddress("service", second);
        assertEquals(first, pool.nextAddress("service", "echo", new Object[0]));
    }
}
