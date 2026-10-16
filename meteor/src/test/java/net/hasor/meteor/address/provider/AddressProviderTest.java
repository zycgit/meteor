/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.provider;
import java.util.Arrays;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.dsl.RouteContext;
import org.junit.Test;
import static org.junit.Assert.*;

public class AddressProviderTest {
    @Test
    public void fixedProviderKeepsItsAddressAcrossServices() throws Exception {
        // 固定地址提供器只持有显式给定的目标地址。
        InterAddress address = new InterAddress("rsf://127.0.0.1:2181/default");
        AddressProvider provider = new InstanceAddressProvider(address);

        // 服务、方法和参数变化都不会改变固定目标。
        RouteContext firstCall = new RouteContext("first", "echo", new Object[] { "a" });
        assertSame(address, provider.get(firstCall));
        RouteContext secondCall = new RouteContext("second", "other", null);
        assertSame(address, provider.get(secondCall));
    }

    @Test
    public void fixedProviderIsNotDistributed() throws Exception {
        // 创建绑定单个地址的提供器。
        InterAddress address = new InterAddress("rsf://127.0.0.1:2181/default");
        AddressProvider provider = new InstanceAddressProvider(address);

        // 固定地址调用不声明分布式选址能力。
        assertFalse(provider.isDistributed());
    }

    @Test
    public void poolProviderIsDistributed() {
        // 创建通过地址池选址的提供器。
        AddressPool pool = new AddressPool();
        AddressProvider provider = new PoolAddressProvider(pool);

        // 池提供器声明分布式选址能力。
        assertTrue(provider.isDistributed());
    }

    @Test
    public void poolProviderUsesXmlRoute() throws Exception {
        // 为服务提供两个候选地址，并安装仅允许第二个端口的规则。
        AddressPool pool = new AddressPool();
        InterAddress first = new InterAddress("rsf://127.0.0.1:2181/default");
        InterAddress second = new InterAddress("rsf://127.0.0.1:2182/default");
        pool.appendAddress("service", Arrays.asList(first, second));
        AddressProvider provider = new PoolAddressProvider(pool);
        String serviceScript = """
                <route>
                    <filter name="selected" from="addresses" item="a" test="a.port == 2182"/>
                    <return value="selected"/>
                </route>
                """;
        assertTrue(pool.updateServiceRoute("service", serviceScript));

        // 通过提供器选址时仍执行地址池的 XML 规则。
        RouteContext input = new RouteContext("service", "echo", new Object[] { "value" });
        assertEquals(second, provider.get(input));
    }

    @Test
    public void clearingPoolRouteRestoresProviderCandidates() throws Exception {
        // 唯一候选地址被当前规则排除，提供器暂时无法选址。
        AddressPool pool = new AddressPool();
        InterAddress first = new InterAddress("rsf://127.0.0.1:2181/default");
        pool.appendAddress("service", first);
        AddressProvider provider = new PoolAddressProvider(pool);
        String serviceScript = """
                <route>
                    <filter name="selected" from="addresses" item="a" test="a.port == 2182"/>
                    <return value="selected"/>
                </route>
                """;
        pool.updateServiceRoute("service", serviceScript);
        RouteContext input = new RouteContext("service", "echo", null);
        assertNull(provider.get(input));

        // 清空规则后，提供器重新返回剩余的候选地址。
        assertTrue(pool.updateServiceRoute("service", ""));
        assertEquals(first, provider.get(input));
    }

    @Test
    public void poolProviderTracksAddressRemoval() throws Exception {
        // 在同一个地址池中登记两个候选地址。
        AddressPool pool = new AddressPool();
        InterAddress first = new InterAddress("rsf://127.0.0.1:2181/default");
        InterAddress second = new InterAddress("rsf://127.0.0.1:2182/default");
        pool.appendAddress("service", Arrays.asList(first, second));
        AddressProvider provider = new PoolAddressProvider(pool);

        // 移除第二个地址后，提供器只能选择第一个地址。
        pool.removeAddress("service", second);
        RouteContext input = new RouteContext("service", "echo", null);
        assertEquals(first, provider.get(input));
    }

    @Test
    public void poolProviderReturnsNullAfterLastAddressRemoval() throws Exception {
        // 先确认提供器能够返回服务的最后一个地址。
        AddressPool pool = new AddressPool();
        InterAddress first = new InterAddress("rsf://127.0.0.1:2181/default");
        pool.appendAddress("service", first);
        AddressProvider provider = new PoolAddressProvider(pool);
        RouteContext input = new RouteContext("service", "echo", null);
        assertEquals(first, provider.get(input));

        // 最后一个地址被移除后，提供器返回无候选结果。
        pool.removeAddress("service", first);
        assertNull(provider.get(input));
    }
}
