/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.util.List;
import java.util.Set;
import net.hasor.meteor.address.dsl.RouteContext;
import net.hasor.meteor.address.route.FlowControlScripts;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AddressPoolProtocolTest {
    private final InterAddress rsf     = new InterAddress("rsf", "127.0.0.1", 28001, "local");
    private final InterAddress jsonrpc = new InterAddress("jsonrpc", "127.0.0.2", 28002, "remote");
    private final InterAddress hessian = new InterAddress("hessian", "127.0.0.3", 28003, "local");

    @Test
    void filtersCandidatesWithoutDeletingOriginalAddresses() {
        // 静态和动态地址都受 scheme 筛选，原始列表仍保留全部协议。
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendStaticAddress("service", List.of(this.rsf, this.jsonrpc));
        pool.appendAddress("service", this.hessian);
        pool.configProtocols(Set.of("jsonrpc"));
        assertEquals(List.of(this.jsonrpc), pool.queryAvailableAddresses("service"));
        assertEquals(List.of(this.jsonrpc), pool.queryLocalUnitAddresses("service"));
        assertEquals(Set.of(this.rsf, this.jsonrpc, this.hessian), Set.copyOf(pool.queryAllAddresses("service")));
    }

    @Test
    void clearingRestrictionRestoresExcludedAddresses() {
        // 先限制为 RSF，再清空支持集合，原始地址重新进入可用列表。
        AddressPool pool = new AddressPool();
        pool.appendAddress("service", List.of(this.rsf, this.jsonrpc));
        pool.configProtocols(Set.of("rsf"));
        assertEquals(List.of(this.rsf), pool.queryAvailableAddresses("service"));
        pool.configProtocols(Set.of());
        assertTrue(pool.getSupportedProtocols("service").isEmpty());
        assertEquals(Set.of(this.rsf, this.jsonrpc), Set.copyOf(pool.queryAvailableAddresses("service")));
    }

    @Test
    void normalizesSchemesAndExposesReadOnlySnapshot() {
        // scheme 统一大小写并去除空白，读取快照不随后续配置改变。
        AddressPool pool = new AddressPool();
        pool.appendAddress("service", List.of(this.rsf, this.jsonrpc));
        pool.configProtocols(List.of(" RSF ", "JSONRPC", ""));
        Set<String> snapshot = pool.getSupportedProtocols("service");
        assertEquals(Set.of("rsf", "jsonrpc"), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("hessian"));
        pool.configProtocols(Set.of("rsf"));
        assertEquals(Set.of("rsf", "jsonrpc"), snapshot);
    }

    @Test
    void protocolUpdateRefreshesCachedAddressSelection() {
        // 先生成选址快照，再把协议收窄到唯一候选，不能继续取到旧快照中的地址。
        AddressPool pool = new AddressPool();
        pool.appendAddress("service", List.of(this.rsf, this.jsonrpc));
        assertNotNull(pool.nextAddress("service", "echo", new Object[0]));
        pool.configProtocols(Set.of("jsonrpc"));
        assertEquals(this.jsonrpc, pool.nextAddress("service", "echo", new Object[0]));
    }

    @Test
    void registryRefreshPreservesRestrictionAndStaticAddresses() {
        // 发现结果替换动态地址，连接器范围和静态地址继续保留。
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("service", this.rsf);
        pool.appendAddress("service", this.jsonrpc);
        pool.configProtocols(Set.of("jsonrpc"));
        InterAddress next = new InterAddress("jsonrpc", "127.0.0.4", 28004, "local");
        pool.refreshAddress("service", List.of(next, this.hessian));
        assertEquals(Set.of("jsonrpc"), pool.getSupportedProtocols("service"));
        assertEquals(List.of(next), pool.queryAvailableAddresses("service"));
        assertEquals(Set.of(this.rsf, next, this.hessian), Set.copyOf(pool.queryAllAddresses("service")));
    }

    @Test
    void changingProtocolsPreservesAddressFailureState() {
        // 恢复协议范围不能把尚未到重试时间的动态地址恢复为有效。
        AddressPool pool = new AddressPool();
        pool.appendAddress("service", List.of(this.rsf, this.jsonrpc));
        pool.invalidAddress(this.rsf);
        pool.configProtocols(Set.of("jsonrpc"));
        pool.configProtocols(Set.of());
        assertEquals(List.of(this.jsonrpc), pool.queryAvailableAddresses("service"));
        assertEquals(List.of(this.rsf), pool.queryInvalidAddresses("service"));
    }

    @Test
    void unmatchedSchemeLeavesNoUsableAddress() {
        // 非空集合不匹配任何地址时，可用列表保持为空，不回退到全量地址。
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("service", this.rsf);
        pool.configProtocols(Set.of("jsonrpc"));
        assertTrue(pool.queryAvailableAddresses("service").isEmpty());
        assertNull(pool.nextAddress("service", "echo", new Object[0]));
        assertEquals(List.of(this.rsf), pool.queryAllAddresses("service"));
    }

    @Test
    void connectorSchemesApplyToFutureAndRegisteredBuckets() {
        // 连接器能力先配置；发现创建和服务登记创建的地址本都继承相同集合。
        AddressPool pool = new AddressPool();
        pool.configProtocols(Set.of("rsf"));
        pool.appendAddress("discovered", List.of(this.rsf, this.jsonrpc));
        AddressBucket prepared = new AddressBucket("registered", "local");
        prepared.newAddress(List.of(this.rsf, this.jsonrpc), AddressTypeEnum.Static);
        pool.registerBucket(prepared);
        assertEquals(List.of(this.rsf), pool.queryAvailableAddresses("discovered"));
        assertEquals(List.of(this.rsf), pool.queryAvailableAddresses("registered"));

        // 连接器支持范围变化时，已有服务和随后新增的服务一同使用新的范围。
        pool.configProtocols(Set.of("jsonrpc"));
        pool.appendAddress("later", List.of(this.rsf, this.jsonrpc));
        assertEquals(List.of(this.jsonrpc), pool.queryAvailableAddresses("discovered"));
        assertEquals(List.of(this.jsonrpc), pool.queryAvailableAddresses("registered"));
        assertEquals(List.of(this.jsonrpc), pool.queryAvailableAddresses("later"));
    }

    @Test
    void callProtocolSchemesDoNotChangeOtherCallsOrStoredAddresses() {
        // 同一服务同时保留两个协议地址，调用上下文分别选择其中一种。
        AddressPool pool = new AddressPool();
        pool.appendAddress("service", List.of(this.rsf, this.jsonrpc));
        RouteContext first = new RouteContext("service", "echo", null);
        first.setAddressSchemes(Set.of("rsf"));
        RouteContext second = new RouteContext("service", "echo", null);
        second.setAddressSchemes(Set.of("jsonrpc"));

        // 本次筛选不写入 bucket；后续调用和完整地址列表保持独立。
        assertEquals(this.rsf, pool.nextAddress(first));
        assertEquals(this.jsonrpc, pool.nextAddress(second));
        assertEquals(List.of(this.rsf, this.jsonrpc), pool.queryAvailableAddresses("service"));
        assertTrue(pool.getSupportedProtocols("service").isEmpty());
    }

    @Test
    void callProtocolSchemesApplyBeforeUnitPreference() {
        // 本单元只有 RSF，远端才有 JSON RPC；全局单元优先原本选择本地地址。
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("service", List.of(this.rsf, this.jsonrpc));
        String control = "<controlSet>" + FlowControlScripts.unit(true, 0, "") + "</controlSet>";
        assertTrue(pool.updateFlowControl("service", control));
        assertEquals(List.of(this.rsf), pool.queryLocalUnitAddresses("service"));

        // 当前调用只允许 JSON RPC，本地不同协议不能遮蔽合法的远端地址。
        RouteContext input = new RouteContext("service", "echo", null);
        input.setAddressSchemes(Set.of("jsonrpc"));
        assertEquals(this.jsonrpc, pool.nextAddress(input));
        assertEquals(List.of(this.rsf, this.jsonrpc), pool.queryAllAddresses("service"));
    }

    @Test
    void callProtocolSchemesPreserveUnitPreferenceForMatchingAddresses() {
        // JSON RPC 同时有本地与远端地址，RSF 也有本地地址。
        AddressPool pool = new AddressPool("local", 30000);
        InterAddress local = new InterAddress("jsonrpc", "127.0.0.4", 28004, "local");
        pool.appendAddress("service", List.of(this.rsf, this.jsonrpc, local));
        String control = "<controlSet>" + FlowControlScripts.unit(true, 0, "") + "</controlSet>";
        assertTrue(pool.updateFlowControl("service", control));

        // 协议筛选之后仍使用相同的单元优先规则，只选本地 JSON RPC。
        RouteContext input = new RouteContext("service", "echo", null);
        input.setAddressSchemes(Set.of("jsonrpc"));
        assertEquals(local, pool.nextAddress(input));
    }

    @Test
    void callProtocolSchemesDoNotBypassRoutingRules() {
        // 当前协议有合法地址，但服务治理明确拒绝全部候选。
        AddressPool pool = new AddressPool();
        pool.appendAddress("service", List.of(this.rsf, this.jsonrpc));
        assertTrue(pool.updateServiceRoute("service", "<route><return value='{}'/></route>"));

        // 不能因为已经按协议筛选就跳过原有路由，也不能回退到其它协议。
        RouteContext input = new RouteContext("service", "echo", null);
        input.setAddressSchemes(Set.of("jsonrpc"));
        assertNull(pool.nextAddress(input));
    }

    @Test
    void emptyCallProtocolSchemesHaveNoCandidates() {
        // 非空地址本遇到显式空的可用 scheme 集合，表示当前声明没有合法挂载。
        AddressPool pool = new AddressPool();
        pool.appendAddress("service", List.of(this.rsf, this.jsonrpc));
        RouteContext input = new RouteContext("service", "echo", null);
        input.setAddressSchemes(Set.of());

        // 空集合不是不限协议；地址本和没有附加限制的另一次调用保持可用。
        assertNull(pool.nextAddress(input));
        assertNotNull(pool.nextAddress("service", "echo", null));
        assertEquals(List.of(this.rsf, this.jsonrpc), pool.queryAllAddresses("service"));
    }

    @Test
    void callProtocolSchemesDoNotRestoreInvalidAddresses() {
        // 当前协议的唯一地址已失效，另一个协议仍然可用。
        AddressPool pool = new AddressPool();
        pool.appendAddress("service", List.of(this.rsf, this.jsonrpc));
        pool.invalidAddress(this.jsonrpc);
        RouteContext input = new RouteContext("service", "echo", null);
        input.setAddressSchemes(Set.of("jsonrpc"));

        // 本次协议筛选只能使用有效快照，不能从全量地址中重新激活失效地址。
        assertNull(pool.nextAddress(input));
        assertEquals(List.of(this.jsonrpc), pool.queryInvalidAddresses("service"));
    }
}
