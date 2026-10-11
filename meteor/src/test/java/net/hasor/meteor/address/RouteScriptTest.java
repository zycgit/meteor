/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.address.dsl.RouteContext;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteScriptTest {
    private final        InterAddress first  = new InterAddress("rsf", "127.0.0.1", 2181, "east");
    private final        InterAddress second = new InterAddress("rsf", "127.0.0.1", 2182, "west");
    private static final String       ALL    = "<route><return value='addresses'/></route>";
    private static final String       PORT   = """
            <route>
                <filter name="selected" from="addresses" item="a" test="a.port == args[0]"/>
                <return value="selected"/>
            </route>
            """;

    private AddressPool pool() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", List.of(this.first, this.second));
        return pool;
    }

    @Test
    public void callsUseActualArgumentsAndLatestAddresses() {
        AddressPool pool = this.pool();
        assertTrue(pool.updateArgsRoute("s", PORT));
        assertEquals(this.first, pool.nextAddress("s", "query", new Object[] { 2181 }));
        assertEquals(this.second, pool.nextAddress("s", "query", new Object[] { 2182 }));

        // 移除地址后即使规则仍命中原端口，也不允许回退到其他目标。
        pool.invalidAddress(this.second);
        assertNull(pool.nextAddress("s", "query", new Object[] { 2182 }));
        pool.appendAddress("s", this.second);
        assertEquals(this.second, pool.nextAddress("s", "query", new Object[] { 2182 }));
    }

    @Test
    public void invalidUpdatesKeepLastValidRuleAndClearingRestoresCandidates() {
        AddressPool pool = this.pool();
        assertTrue(pool.updateServiceRoute("s", PORT));
        assertFalse(pool.updateServiceRoute("s", PORT));
        assertFalse(pool.updateServiceRoute("s", "<route><bad/></route>"));
        assertEquals(PORT, pool.serviceRoute("s"));
        assertEquals(this.second, pool.nextAddress("s", "query", new Object[] { 2182 }));
        assertTrue(pool.updateServiceRoute("s", " "));
        assertNull(pool.serviceRoute("s"));
        assertFalse(pool.updateServiceRoute("s", null));
        pool.removeAddress("s", this.second);
        assertEquals(this.first, pool.nextAddress("s", "query", null));
    }

    @Test
    public void levelsFilterInOrderAndClearingOneDoesNotClearOthers() {
        AddressPool pool = this.pool();
        pool.updateServiceRoute("s", ALL);
        String methodRule = """
                <route>
                    <if test="utils.equals(method.name, 'blocked')"><return value="{}"/></if>
                    <return value="addresses"/>
                </route>
                """;
        pool.updateMethodRoute("s", methodRule);
        pool.updateArgsRoute("s", PORT);
        assertNull(pool.nextAddress("s", "blocked", new Object[] { 2181 }));
        assertNull(pool.nextAddress("s", "blocked", null));
        assertEquals(this.first, pool.nextAddress("s", "query", new Object[] { 2181 }));
        assertTrue(pool.updateMethodRoute("s", null));
        assertEquals(this.first, pool.nextAddress("s", "blocked", new Object[] { 2181 }));
        assertEquals(PORT, pool.argsRoute("s"));
    }

    @Test
    public void runtimeFailurePropagatesAndAnotherServiceIsUnaffected() {
        AddressPool pool = this.pool();
        pool.appendAddress("other", this.first);
        pool.updateServiceRoute("s", "<route><return value='args[1]'/></route>");
        assertThrows(IllegalArgumentException.class, () -> pool.nextAddress("s", "query", new Object[0]));
        assertEquals(this.first, pool.nextAddress("other", "query", null));
        pool.updateServiceRoute("s", ALL);
        assertNotNull(pool.nextAddress("s", "query", null));
    }

    @Test
    public void environmentAndMetadataAreReadOnEverySelection() {
        AddressPool pool = this.pool();
        String xml = """
                <route>
                    <filter name="selected" from="addresses" item="a" test="a.port == service.meta[env.key]"/>
                    <return value="selected"/>
                </route>
                """;
        pool.updateServiceRoute("s", xml);
        RouteContext input = new RouteContext("s", "query", null);
        input.getEnv().put("key", "port");
        input.getService().put("meta", Map.of("port", 2181));
        assertEquals(this.first, pool.nextAddress(input));
        input.getService().put("meta", Map.of("port", 2182));
        assertEquals(this.second, pool.nextAddress(input));
    }

    @Test
    public void slowEvaluationDoesNotHoldAddressPoolLockOrOverwriteNewSnapshot() throws Exception {
        AddressPool pool = this.pool();
        BlockingInput argument = new BlockingInput();
        String rule = """
                <route>
                    <bind name="port" value="args[0].port"/>
                    <filter name="selected" from="addresses" item="a" test="a.port == port"/>
                    <return value="selected"/>
                </route>
                """;
        pool.updateArgsRoute("s", rule);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var selecting = executor.submit(() -> pool.nextAddress("s", "query", new Object[] { argument }));
            assertTrue(argument.entered.await(5, TimeUnit.SECONDS));

            // 执行中的规则不阻止地址更新；旧调用结束也不会覆盖新快照。
            pool.refreshAddress("s", List.of(this.first));
            pool.updateArgsRoute("s", ALL);
            pool.appendAddress("other", this.second);
            argument.resume.countDown();
            selecting.get(5, TimeUnit.SECONDS);
            assertEquals(this.first, pool.nextAddress("s", "query", null));
            assertEquals(this.second, pool.nextAddress("other", "query", null));
        } finally {
            argument.resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    public static final class BlockingInput {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch resume  = new CountDownLatch(1);

        public int getPort() throws InterruptedException {
            this.entered.countDown();
            if (!this.resume.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for address update");
            }
            return 2182;
        }
    }
}
