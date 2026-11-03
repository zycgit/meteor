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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AddressPoolProtocolTest {
    private final InterAddress rsf = new InterAddress("rsf", "127.0.0.1", 28001, "local");
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
}
