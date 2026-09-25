/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import net.hasor.rsf.address.route.ArgsKey;
import net.hasor.rsf.address.route.FlowControlTest;

public class RouteScriptTest extends ScriptTestSupport {
    private final InterAddress a = new InterAddress("127.0.0.1", 8000, "zone");
    private final InterAddress b = new InterAddress("127.0.0.2", 8000, "zone");
    private final InterAddress c = new InterAddress("127.0.0.3", 8000, "zone");

    private AddressPool pool() {
        AddressPool pool = new AddressPool("zone", 30000);
        pool.appendAddress("s", Arrays.asList(this.a, this.b, this.c));
        return pool;
    }

    private void routes(AddressPool pool) {
        pool.updateServiceRoute("s", script("service", (id, all) -> Collections.singletonList(this.a.getHostPort())));
        pool.updateMethodRoute("s", script("method", (id, all) -> Collections.singletonMap("get", Collections.singletonList(this.b.getHostPort()))));
        pool.updateArgsRoute("s", script("args", (id, all) -> Collections.singletonMap("get", Collections.singletonMap("vip-", Collections.singletonList(this.c.getHostPort())))));
    }

    @Test
    public void argsOverrideMethodAndMethodOverridesService() {
        AddressPool pool = pool();
        routes(pool);
        assertEquals(this.c, pool.nextAddress("s", "get", new Object[] { "vip" }));
        assertEquals(this.b, pool.nextAddress("s", "get", new Object[] { "normal" }));
        assertEquals(this.a, pool.nextAddress("s", "other", null));
        assertEquals("service", pool.serviceRoute("s"));
        assertEquals("method", pool.methodRoute("s"));
        assertEquals("args", pool.argsRoute("s"));
    }

    @Test
    public void invalidatedRouteFallsBackAndAppendRestoresIt() {
        AddressPool pool = pool();
        routes(pool);
        pool.invalidAddress(this.c);
        assertEquals(this.b, pool.nextAddress("s", "get", new Object[] { "vip" }));
        pool.invalidAddress(this.b);
        assertEquals(this.a, pool.nextAddress("s", "get", new Object[] { "vip" }));
        pool.appendAddress("s", this.c);
        assertEquals(this.c, pool.nextAddress("s", "get", new Object[] { "vip" }));
    }

    @Test
    public void scriptsAreEvaluatedOnRefreshNotEveryRequest() {
        AddressPool pool = pool();
        AtomicInteger calls = new AtomicInteger();
        pool.updateServiceRoute("s", script("count", (id, all) -> {
            assertEquals("s", id);
            assertEquals(3, all.size());
            calls.incrementAndGet();
            return Collections.singletonList(this.a.getHostPort());
        }));
        int initial = calls.get();
        assertTrue(initial > 0);
        for (int i = 0; i < 10; i++) {
            assertEquals(this.a, pool.nextAddress("s", "get", null));
        }
        assertEquals(initial, calls.get());
        pool.refreshAddressCache();
        assertTrue(calls.get() > initial);
    }

    @Test
    public void emptyUnknownAndThrowingServiceRoutesFallBackToCandidates() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        pool.updateServiceRoute("s", script("empty", (id, all) -> Collections.emptyList()));
        assertEquals(this.a, pool.nextAddress("s", "get", null));
        pool.updateServiceRoute("s", script("unknown", (id, all) -> Collections.singletonList("127.0.0.9:8000")));
        assertEquals(this.a, pool.nextAddress("s", "get", null));
        pool.updateServiceRoute("s", script("throws", (id, all) -> {
            throw new IllegalStateException("test");
        }));
        assertEquals(this.a, pool.nextAddress("s", "get", null));
    }

    @Test
    public void wrongTopLevelScriptResultTypesFallBackToCandidates() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        pool.updateServiceRoute("s", script("wrong-service-type", (id, all) -> 42));
        pool.updateMethodRoute("s", script("wrong-method-type", (id, all) -> Collections.emptyList()));
        pool.updateArgsRoute("s", script("wrong-args-type", (id, all) -> Collections.emptyList()));
        assertEquals(this.a, pool.nextAddress("s", "get", null));
    }

    @Test
    public void clearingServiceRouteDisablesCompiledScript() {
        AddressPool pool = pool();
        routes(pool);
        assertTrue(pool.updateServiceRoute("s", ""));
        assertNull(pool.serviceRoute("s"));
        assertFalse("Clearing source must also clear the executable", pool.getBucket("s").getRuleRef().getServiceLevel().isEnable());
    }

    @Test
    public void clearingMethodRouteFallsBackToService() {
        AddressPool pool = pool();
        routes(pool);
        pool.updateMethodRoute("s", null);
        assertEquals(this.a, pool.nextAddress("s", "get", new Object[] { "normal" }));
    }

    @Test
    public void clearingArgsRouteFallsBackToMethod() {
        AddressPool pool = pool();
        routes(pool);
        pool.updateArgsRoute("s", " ");
        assertEquals(this.b, pool.nextAddress("s", "get", new Object[] { "vip" }));
    }

    @Test
    public void failedCompilationPreservesLastWorkingScript() {
        AddressBucket bucket = pool().getBucket("s");
        String valid = script("valid", (id, all) -> Collections.singletonList(this.a.getHostPort()));
        assertTrue(bucket.updateRoute(RouteTypeEnum.ServiceLevel, valid));
        assertFalse(bucket.updateRoute(RouteTypeEnum.ServiceLevel, "invalid"));
        assertEquals(valid, bucket.getRuleRef().getServiceLevel().getScript());
        assertFalse(bucket.updateRoute(RouteTypeEnum.ServiceLevel, valid));
    }

    @Test
    public void updatingScriptDoesNotMutatePreviouslyPublishedRuleSnapshot() {
        AddressBucket bucket = pool().getBucket("s");
        bucket.updateRoute(RouteTypeEnum.ServiceLevel, script("old", (id, all) -> Collections.emptyList()));
        RuleRef previous = bucket.getRuleRef();
        bucket.updateRoute(RouteTypeEnum.ServiceLevel, script("new", (id, all) -> Collections.emptyList()));
        assertEquals("old", previous.getServiceLevel().getScript());
        assertEquals("new", bucket.getRuleRef().getServiceLevel().getScript());
    }

    @Test
    public void poolReportsFailedCompilation() {
        AddressPool pool = pool();
        assertFalse("Update failure must reach the caller", pool.updateServiceRoute("s", "invalid"));
    }

    @Test
    public void absentSpiProviderReportsRouteNotApplied() {
        Thread.currentThread().setContextClassLoader(withoutScriptProvider());
        AddressPool pool = pool();
        assertFalse(pool.updateServiceRoute("s", "any source"));
    }

    @Test
    public void malformedNestedScriptResultDoesNotBreakAddressUpdates() {
        AddressPool pool = pool();
        pool.updateMethodRoute("s", script("bad-result", (id, all) -> Collections.singletonMap("get", null)));
        assertNotNull(pool.nextAddress("s", "get", null));
    }

    @Test
    public void argumentKeyCanBeCustomized() {
        AddressPool pool = new AddressPool() {
            @Override
            protected ArgsKey getArgsKey() {
                return (service, method, args) -> "tenant";
            }
        };
        pool.appendAddress("s", Arrays.asList(this.a, this.b));
        pool.updateArgsRoute("s", script("custom", (id, all) -> Collections.singletonMap("get", Collections.singletonMap("tenant", Collections.singletonList(this.b.getHostPort())))));
        assertEquals(this.b, pool.nextAddress("s", "get", new Object[] { new Object() }));
    }

    @Test
    public void nullArgumentKeyProviderFallsBackToService() {
        AddressPool pool = new AddressPool() {
            @Override
            protected ArgsKey getArgsKey() {
                return null;
            }
        };
        pool.appendAddress("s", this.a);
        pool.updateArgsRoute("s", script("unused", (id, all) -> Collections.emptyMap()));
        assertEquals(this.a, pool.nextAddress("s", "get", null));
    }

    @Test
    public void nullAndEmptyNestedRouteMapsFallBackToService() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.a);
        pool.updateMethodRoute("s", script("null-method", (id, all) -> null));
        pool.updateArgsRoute("s", script("empty-args", (id, all) -> Collections.emptyMap()));
        assertEquals(this.a, pool.nextAddress("s", "get", null));
        pool.updateMethodRoute("s", script("empty-method-addresses", (id, all) -> Collections.singletonMap("get", Collections.emptyList())));
        pool.updateArgsRoute("s", script("empty-args-addresses", (id, all) -> Collections.singletonMap("get", Collections.singletonMap("vip-", Collections.emptyList()))));
        assertEquals(this.a, pool.nextAddress("s", "get", new Object[] { "vip" }));
    }

    @Test
    public void serviceRoutingCanOverrideLocalUnitPreference() {
        AddressPool pool = new AddressPool("other", 30000);
        InterAddress local = new InterAddress("127.0.0.9", 8000, "other");
        pool.appendAddress("s", Arrays.asList(local, this.a));
        pool.updateFlowControl("s", "<controlSet>" + FlowControlTest.unit(true, 0.5, "192.0.2.*") + "</controlSet>");
        assertEquals(local, pool.nextAddress("s", "get", null));
        pool.updateServiceRoute("s", script("override-unit", (id, all) -> Collections.singletonList(this.a.getHostPort())));
        assertEquals(this.a, pool.nextAddress("s", "get", null));
    }

    @Test
    public void scriptMayReturnHostnameFromItsInputAddresses() {
        AddressPool pool = new AddressPool("local", 30000);
        InterAddress hostname = new InterAddress("localhost", 8000, "remote");
        InterAddress local = new InterAddress("127.0.0.9", 8000, "local");
        pool.appendAddress("s", Arrays.asList(hostname, local));
        pool.updateFlowControl("s", "<controlSet>" + FlowControlTest.unit(true, 0.5, "192.0.2.*") + "</controlSet>");
        pool.updateServiceRoute("s", script("select-hostname", (id, all) -> {
            assertTrue(all.contains("localhost:8000"));
            return Collections.singletonList("localhost:8000");
        }));
        assertEquals("A valid address supplied to a script must be recognized when returned", hostname, pool.nextAddress("s", "get", null));
    }
}
