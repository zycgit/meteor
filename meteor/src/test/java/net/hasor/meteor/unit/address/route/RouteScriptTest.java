/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.address.route;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.dsl.RouteContext;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteScriptTest {
    private static final String       ALL      = "<route><return value='addresses'/></route>";
    private static final String       PORT     = """
            <route>
                <filter name="selected" from="addresses" item="a" test="a.port == args[0]"/>
                <return value="selected"/>
            </route>
            """;
    private static final String       METHOD   = """
            <route>
                <if test="utils.equals(method.name, 'blocked')"><return value="{}"/></if>
                <return value="addresses"/>
            </route>
            """;
    private static final String       METADATA = """
            <route>
                <filter name="selected" from="addresses" item="a" test="a.port == service.meta[env.key]"/>
                <return value="selected"/>
            </route>
            """;
    private static final String       BLOCKING = """
            <route>
                <bind name="port" value="args[0].port"/>
                <filter name="selected" from="addresses" item="a" test="a.port == port"/>
                <return value="selected"/>
            </route>
            """;
    private final        InterAddress first    = new InterAddress("rsf", "127.0.0.1", 2181, "east");
    private final        InterAddress second   = new InterAddress("rsf", "127.0.0.1", 2182, "west");

    @Test
    public void argsRouteUsesCurrentCallArguments() {
        // 为同一服务准备两个候选地址，并按调用参数筛选端口。
        AddressPool pool = this.pool();
        assertTrue(pool.updateArgsRoute("s", PORT));

        // 连续调用使用各自的参数，不能复用上一次的筛选结果。
        assertEquals(this.first, pool.nextAddress("s", "query", new Object[] { 2181 }));
        assertEquals(this.second, pool.nextAddress("s", "query", new Object[] { 2182 }));
    }

    @Test
    public void invalidAddressDoesNotFallBackToOtherCandidates() {
        // 确认规则原本能够命中随后将失效的地址。
        AddressPool pool = this.pool();
        pool.updateArgsRoute("s", PORT);
        assertEquals(this.second, pool.nextAddress("s", "query", new Object[] { 2182 }));

        // 地址失效后，本次筛选没有候选地址，不回退到另一端口。
        pool.invalidAddress(this.second);
        assertNull(pool.nextAddress("s", "query", new Object[] { 2182 }));
    }

    @Test
    public void reactivatedAddressBecomesSelectableAgain() {
        // 让规则目标暂时失效，形成无法选址的初始状态。
        AddressPool pool = this.pool();
        pool.updateArgsRoute("s", PORT);
        pool.invalidAddress(this.second);
        assertNull(pool.nextAddress("s", "query", new Object[] { 2182 }));

        // 重新登记同一地址后，规则读取新的可用地址快照。
        pool.appendAddress("s", this.second);
        assertEquals(this.second, pool.nextAddress("s", "query", new Object[] { 2182 }));
    }

    @Test
    public void identicalRuleUpdateReportsNoChange() {
        // 先安装一条有效的服务级路由。
        AddressPool pool = this.pool();
        assertTrue(pool.updateServiceRoute("s", PORT));

        // 重复提交相同正文不会产生更新，原规则继续参与选址。
        assertFalse(pool.updateServiceRoute("s", PORT));
        assertEquals(PORT, pool.serviceRoute("s"));
        assertEquals(this.second, pool.nextAddress("s", "query", new Object[] { 2182 }));
    }

    @Test
    public void invalidUpdateKeepsLastValidRule() {
        // 先安装能够选择第二个地址的有效规则。
        AddressPool pool = this.pool();
        assertTrue(pool.updateServiceRoute("s", PORT));

        // 非法 XML 步骤被拒绝后，正文和实际路由结果均保留。
        assertFalse(pool.updateServiceRoute("s", "<route><bad/></route>"));
        assertEquals(PORT, pool.serviceRoute("s"));
        assertEquals(this.second, pool.nextAddress("s", "query", new Object[] { 2182 }));
    }

    @Test
    public void blankRuleClearsRoutingAndRestoresCandidates() {
        // 移除规则指定的端口，使剩余地址被原规则排除。
        AddressPool pool = this.pool();
        pool.updateServiceRoute("s", PORT);
        pool.removeAddress("s", this.second);
        assertNull(pool.nextAddress("s", "query", new Object[] { 2182 }));

        // 空白正文清除规则，剩余地址重新成为可选目标。
        assertTrue(pool.updateServiceRoute("s", " "));
        assertNull(pool.serviceRoute("s"));
        assertEquals(this.first, pool.nextAddress("s", "query", null));
    }

    @Test
    public void clearingAlreadyClearedRuleReportsNoChange() {
        // 将已安装的规则清空，建立重复清除的初始状态。
        AddressPool pool = this.pool();
        pool.updateServiceRoute("s", PORT);
        pool.updateServiceRoute("s", " ");

        // 再提交 null 不产生更新，规则仍为空。
        assertFalse(pool.updateServiceRoute("s", null));
        assertNull(pool.serviceRoute("s"));
    }

    @Test
    public void methodRouteCanRejectServiceCandidates() {
        // 服务级放行候选地址，方法级拒绝 blocked，参数级选择指定端口。
        AddressPool pool = this.pool();
        pool.updateServiceRoute("s", ALL);
        pool.updateMethodRoute("s", METHOD);
        pool.updateArgsRoute("s", PORT);

        // 方法级拒绝后，匹配参数级端口的地址也不能重新进入结果。
        assertNull(pool.nextAddress("s", "blocked", new Object[] { 2181 }));
    }

    @Test
    public void emptyMethodResultSkipsArgumentEvaluation() {
        // 后续参数规则需要 args[0]，方法级则提前返回空候选集。
        AddressPool pool = this.pool();
        pool.updateServiceRoute("s", ALL);
        pool.updateMethodRoute("s", METHOD);
        pool.updateArgsRoute("s", "<route><return value='args[0]'/></route>");

        // 即使没有参数，也只返回空选址结果，不执行会越界的参数表达式。
        assertNull(pool.nextAddress("s", "blocked", null));
    }

    @Test
    public void argsRouteFiltersCandidatesAcceptedByEarlierLevels() {
        // 服务级和方法级均放行 query，最后由参数级缩小候选范围。
        AddressPool pool = this.pool();
        pool.updateServiceRoute("s", ALL);
        pool.updateMethodRoute("s", METHOD);
        pool.updateArgsRoute("s", PORT);

        // 参数级规则选择本次调用指定的唯一端口。
        assertEquals(this.first, pool.nextAddress("s", "query", new Object[] { 2181 }));
    }

    @Test
    public void clearingMethodRoutePreservesOtherLevels() {
        // 安装三个层次的规则，并确认方法级当前会拒绝调用。
        AddressPool pool = this.pool();
        pool.updateServiceRoute("s", ALL);
        pool.updateMethodRoute("s", METHOD);
        pool.updateArgsRoute("s", PORT);
        assertNull(pool.nextAddress("s", "blocked", new Object[] { 2181 }));

        // 只清除方法级后，服务级和参数级正文及参数筛选仍然有效。
        assertTrue(pool.updateMethodRoute("s", null));
        assertNull(pool.methodRoute("s"));
        assertEquals(ALL, pool.serviceRoute("s"));
        assertEquals(PORT, pool.argsRoute("s"));
        assertEquals(this.first, pool.nextAddress("s", "blocked", new Object[] { 2181 }));
    }

    @Test
    public void runtimeFailureDoesNotAffectAnotherService() {
        // 只给目标服务安装会访问越界参数的规则。
        AddressPool pool = this.pool();
        pool.appendAddress("other", this.first);
        pool.updateServiceRoute("s", "<route><return value='args[1]'/></route>");

        // 目标服务向调用方传播异常，另一服务仍能正常选址。
        assertThrows(IllegalArgumentException.class, () -> pool.nextAddress("s", "query", new Object[0]));
        assertEquals(this.first, pool.nextAddress("other", "query", null));
    }

    @Test
    public void validReplacementRestoresSelectionAfterRuntimeFailure() {
        // 先触发一次运行时失败，确认失败规则已经生效。
        AddressPool pool = this.pool();
        pool.updateServiceRoute("s", "<route><return value='args[1]'/></route>");
        assertThrows(IllegalArgumentException.class, () -> pool.nextAddress("s", "query", new Object[0]));

        // 替换为有效规则后，同一服务恢复选址。
        assertTrue(pool.updateServiceRoute("s", ALL));
        assertNotNull(pool.nextAddress("s", "query", null));
    }

    @Test
    public void serviceMetadataIsReadOnEverySelection() {
        // 通过环境中的键读取服务元信息，首次选择第一个端口。
        AddressPool pool = this.pool();
        pool.updateServiceRoute("s", METADATA);
        RouteContext input = new RouteContext("s", "query", null);
        input.getEnv().put("key", "port");
        input.getService().put("meta", Map.of("port", 2181));
        assertEquals(this.first, pool.nextAddress(input));

        // 仅更新元信息，下一次选择必须读取新端口。
        input.getService().put("meta", Map.of("port", 2182));
        assertEquals(this.second, pool.nextAddress(input));
    }

    @Test
    public void environmentIsReadOnEverySelection() {
        // 固定两个元信息端口，由环境中的键决定使用哪一项。
        AddressPool pool = this.pool();
        pool.updateServiceRoute("s", METADATA);
        RouteContext input = new RouteContext("s", "query", null);
        input.getService().put("meta", Map.of("firstPort", 2181, "secondPort", 2182));
        input.getEnv().put("key", "firstPort");
        assertEquals(this.first, pool.nextAddress(input));

        // 仅更新环境键，下一次选择必须读取另一项元信息。
        input.getEnv().put("key", "secondPort");
        assertEquals(this.second, pool.nextAddress(input));
    }

    @Test
    public void slowEvaluationDoesNotBlockAddressUpdates() throws Exception {
        // 规则通过参数 getter 暂停，留出并发更新地址的观察窗口。
        AddressPool pool = this.pool();
        BlockingInput argument = new BlockingInput();
        pool.updateArgsRoute("s", BLOCKING);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var selecting = executor.submit(() -> pool.nextAddress("s", "query", new Object[] { argument }));
            assertTrue(argument.entered.await(5, TimeUnit.SECONDS));

            // 规则仍在等待时，原服务更新和新服务登记都应在期限内完成。
            var updating = executor.submit(() -> {
                pool.refreshAddress("s", List.of(this.first));
                pool.appendAddress("other", this.second);
            });
            updating.get(5, TimeUnit.SECONDS);
            assertEquals(this.second, pool.nextAddress("other", "query", null));

            // 放行原调用并等待完成，确保后台任务不会泄漏到其他用例。
            argument.resume.countDown();
            selecting.get(5, TimeUnit.SECONDS);
        } finally {
            argument.resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void completedEvaluationDoesNotOverwriteNewSnapshot() throws Exception {
        // 让一次选址停留在旧规则和旧地址快照上。
        AddressPool pool = this.pool();
        BlockingInput argument = new BlockingInput();
        pool.updateArgsRoute("s", BLOCKING);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var selecting = executor.submit(() -> pool.nextAddress("s", "query", new Object[] { argument }));
            assertTrue(argument.entered.await(5, TimeUnit.SECONDS));

            // 原调用完成前，发布只包含第一个地址的新快照和新规则。
            var updating = executor.submit(() -> {
                pool.refreshAddress("s", List.of(this.first));
                pool.updateArgsRoute("s", ALL);
                pool.appendAddress("other", this.second);
            });
            updating.get(5, TimeUnit.SECONDS);
            argument.resume.countDown();
            selecting.get(5, TimeUnit.SECONDS);

            // 旧调用完成后，新地址、新规则及其他服务的快照仍然可见。
            assertEquals(this.first, pool.nextAddress("s", "query", null));
            assertEquals(this.second, pool.nextAddress("other", "query", null));
        } finally {
            argument.resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private AddressPool pool() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", List.of(this.first, this.second));
        return pool;
    }

    public static final class BlockingInput {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch resume  = new CountDownLatch(1);

        public int getPort() throws InterruptedException {
            // 通知用例规则已进入求值阶段，再有界等待地址更新完成。
            this.entered.countDown();
            if (!this.resume.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for address update");
            }
            return 2182;
        }
    }
}
