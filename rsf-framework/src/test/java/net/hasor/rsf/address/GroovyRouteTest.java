/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class GroovyRouteTest {
    @Test
    public void addressComponentLoadsGroovyThroughSpiAndKeepsLastValidRule() throws Exception {
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

    @Test
    public void realGroovyMapsApplyArgumentMethodAndServicePrecedence() {
        AddressPool pool = new AddressPool();
        InterAddress service = new InterAddress("rsf", "127.0.0.1", 2181, "default");
        InterAddress method = new InterAddress("rsf", "127.0.0.1", 2182, "default");
        InterAddress argument = new InterAddress("rsf", "127.0.0.1", 2183, "default");
        pool.appendAddress("service", Arrays.asList(service, method, argument));
        assertTrue(pool.updateServiceRoute("service", "def evalAddress(id, addresses) { [addresses.find { it.endsWith(':2181') }] }"));
        assertTrue(pool.updateMethodRoute("service", "def evalAddress(id, addresses) { [echo: [addresses.find { it.endsWith(':2182') }]] }"));
        assertTrue(pool.updateArgsRoute("service", "def evalAddress(id, addresses) { [echo: ['hot-': [addresses.find { it.endsWith(':2183') }]]] }"));
        assertEquals(argument, pool.nextAddress("service", "echo", new Object[] { "hot" }));
        assertEquals(method, pool.nextAddress("service", "echo", new Object[] { "cold" }));
        assertEquals(service, pool.nextAddress("service", "other", new Object[] { "hot" }));
        assertFalse(pool.updateMethodRoute("service", "def broken {"));
        assertEquals(method, pool.nextAddress("service", "echo", new Object[] { "cold" }));
        assertTrue(pool.updateArgsRoute("service", ""));
        assertEquals(method, pool.nextAddress("service", "echo", new Object[] { "hot" }));
    }

    @Test
    public void groovyRuntimeFailuresAndMissingFunctionsFallBackAndAllowReplacement() {
        AddressPool pool = new AddressPool();
        InterAddress address = new InterAddress("rsf", "127.0.0.1", 2181, "default");
        pool.appendAddress("service", address);
        for (String script : Arrays.asList("def evalAddress(id, addresses) { throw new IllegalStateException('route failed') }", "def differentFunction() { [] }", "def evalAddress(id, addresses) { 42 }")) {
            assertTrue(pool.updateServiceRoute("service", script));
            assertEquals(script, address, pool.nextAddress("service", "echo", new Object[0]));
        }
        assertTrue(pool.updateServiceRoute("service", "def evalAddress(id, addresses) { addresses }"));
        assertEquals(address, pool.nextAddress("service", "echo", new Object[0]));
    }

    @Test
    public void compiledGroovyRulesDoNotShareFunctionsAcrossServices() {
        AddressPool pool = new AddressPool();
        InterAddress first = new InterAddress("rsf", "127.0.0.1", 2181, "default");
        InterAddress second = new InterAddress("rsf", "127.0.0.1", 2182, "default");
        pool.appendAddress("first", Arrays.asList(first, second));
        pool.appendAddress("second", Arrays.asList(first, second));
        assertTrue(pool.updateServiceRoute("first", "def evalAddress(id, addresses) { [addresses.find { it.endsWith(':2181') }] }"));
        assertTrue(pool.updateServiceRoute("second", "def evalAddress(id, addresses) { [addresses.find { it.endsWith(':2182') }] }"));
        assertEquals(first, pool.nextAddress("first", "echo", new Object[0]));
        assertEquals(second, pool.nextAddress("second", "echo", new Object[0]));
        assertTrue(pool.updateServiceRoute("second", "def evalAddress(id, addresses) { addresses.take(1) }"));
        assertEquals(first, pool.nextAddress("first", "echo", new Object[0]));
    }
}
