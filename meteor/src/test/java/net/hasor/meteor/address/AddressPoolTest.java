/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.address.route.speed.SpeedFlowControl;
import org.junit.Test;
import static org.junit.Assert.*;

public class AddressPoolTest {
    private final InterAddress localAddress  = new InterAddress("127.0.0.1", 8000, "local");
    private final InterAddress remoteAddress = new InterAddress("127.0.0.2", 8000, "remote");

    @Test
    public void missingServiceHasNoAddressOrConfiguration() {
        // 未注册服务没有地址、路由或流控配置，查询也不会创建地址桶。
        AddressPool pool = new AddressPool();
        assertNull(pool.nextAddress("missing", "get", null));
        assertNull(pool.queryAllAddresses("missing"));
        assertNull(pool.queryAvailableAddresses("missing"));
        assertNull(pool.queryInvalidAddresses("missing"));
        assertNull(pool.queryLocalUnitAddresses("missing"));
        assertNull(pool.serviceRoute("missing"));
        assertNull(pool.methodRoute("missing"));
        assertNull(pool.argsRoute("missing"));
        assertNull(pool.flowControl("missing"));
        assertTrue(pool.getBucketNames().isEmpty());
    }

    @Test
    public void removingMissingBucketDoesNotCreateService() {
        // 删除不存在的服务返回未删除，且不生成空地址桶。
        AddressPool pool = new AddressPool();
        assertFalse(pool.removeBucket("missing"));
        assertTrue(pool.getBucketNames().isEmpty());
    }

    @Test
    public void updatingMissingServiceRouteDoesNotCreateService() {
        // 不存在服务的路由更新失败，不能顺带注册服务。
        AddressPool pool = new AddressPool();
        assertFalse(pool.updateServiceRoute("missing", "script"));
        assertTrue(pool.getBucketNames().isEmpty());
    }

    @Test
    public void updatingMissingFlowControlDoesNotCreateService() {
        // 不存在服务的流控更新失败，不能顺带注册服务。
        AddressPool pool = new AddressPool();
        assertFalse(pool.updateFlowControl("missing", "<controlSet></controlSet>"));
        assertTrue(pool.getBucketNames().isEmpty());
    }

    @Test
    public void removingAddressFromMissingServiceDoesNotCreateService() {
        // 对不存在服务撤销地址保持空地址池。
        AddressPool pool = new AddressPool();
        pool.removeAddress("missing", this.localAddress);
        assertTrue(pool.getBucketNames().isEmpty());
    }

    @Test
    public void refreshingMissingServiceDoesNotCreateService() {
        // 注册中心刷新仅作用于已注册的服务。
        AddressPool pool = new AddressPool();
        pool.refreshAddress("missing", Collections.singletonList(this.localAddress));
        assertTrue(pool.getBucketNames().isEmpty());
    }

    @Test
    public void invalidationAndAppendReactivateDynamicAddress() {
        // 注册动态地址并建立可用的选址结果。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.localAddress);
        assertEquals(this.localAddress, pool.nextAddress("s", "get", null));

        // 动态地址失效后停止选址，再次上报后恢复且不重复加入。
        pool.invalidAddress(this.localAddress);
        assertNull(pool.nextAddress("s", "get", null));
        assertEquals(Collections.singletonList(this.localAddress), pool.queryInvalidAddresses("s"));
        pool.appendAddress("s", this.localAddress);
        assertEquals(this.localAddress, pool.nextAddress("s", "get", null));
        assertTrue(pool.queryInvalidAddresses("s").isEmpty());
        assertEquals(1, pool.queryAllAddresses("s").size());
    }

    @Test
    public void staticAddressIgnoresInvalidation() {
        // 静态配置的地址不受动态失效通知影响。
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("s", this.localAddress);
        pool.invalidAddress(this.localAddress);
        assertEquals(this.localAddress, pool.nextAddress("s", "get", null));
        assertTrue(pool.queryInvalidAddresses("s").isEmpty());
    }

    @Test
    public void staticAddressCanBeExplicitlyRemoved() {
        // 先建立静态地址的选址缓存。
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("s", this.localAddress);
        assertEquals(this.localAddress, pool.nextAddress("s", "get", null));

        // 显式移除后，缓存和静态地址集合同时失效。
        pool.removeAddress("s", this.localAddress);
        assertNull(pool.nextAddress("s", "get", null));
        assertTrue(pool.getBucket("s").getStaticAddresses().isEmpty());
    }

    @Test
    public void globalAddressRemovalInvalidatesAffectedServicesOnly() {
        // 两个服务共享待删除地址，第三个服务只使用无关地址。
        AddressPool pool = new AddressPool();
        pool.appendAddress("one", Arrays.asList(this.localAddress, this.remoteAddress));
        pool.appendAddress("two", this.localAddress);
        pool.appendAddress("unrelated", this.remoteAddress);
        assertNotNull(pool.nextAddress("one", "get", null));
        assertEquals(this.localAddress, pool.nextAddress("two", "get", null));

        // 全局撤销共享地址后，相关缓存更新，无关服务保持可用。
        pool.removeAddress(this.localAddress);
        assertEquals(this.remoteAddress, pool.nextAddress("one", "get", null));
        assertNull(pool.nextAddress("two", "get", null));
        assertEquals(this.remoteAddress, pool.nextAddress("unrelated", "get", null));
    }

    @Test
    public void removingBucketInvalidatesOnlyItsSelectionCache() {
        // 先为目标服务和另一个服务建立选址缓存。
        AddressPool pool = new AddressPool();
        pool.appendAddress("one", this.localAddress);
        pool.appendAddress("two", this.remoteAddress);
        assertEquals(this.localAddress, pool.nextAddress("one", "get", null));
        assertEquals(this.remoteAddress, pool.nextAddress("two", "get", null));

        // 服务删除可重复执行，且不影响另一个服务的选址。
        assertTrue(pool.removeBucket("one"));
        assertFalse(pool.removeBucket("one"));
        assertNull(pool.nextAddress("one", "get", null));
        assertEquals(this.remoteAddress, pool.nextAddress("two", "get", null));
    }

    @Test
    public void replaceDynamicAddressesUpdatesCachedSelection() {
        // 先选择旧地址，再用注册中心快照替换它。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.localAddress);
        assertEquals(this.localAddress, pool.nextAddress("s", "get", null));

        // 新快照同时替换查询结果和缓存选址。
        pool.refreshAddress("s", Collections.singletonList(this.remoteAddress));
        assertEquals(Collections.singletonList(this.remoteAddress), pool.queryAllAddresses("s"));
        assertEquals(this.remoteAddress, pool.nextAddress("s", "get", null));
    }

    @Test
    public void emptyRegistryRefreshRemovesLastDynamicProvider() {
        // 空注册中心快照撤销最后一个动态提供者及其选址缓存。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.localAddress);
        assertEquals(this.localAddress, pool.nextAddress("s", "get", null));
        pool.refreshAddress("s", Collections.emptyList());
        assertNull(pool.nextAddress("s", "get", null));
        assertTrue(pool.queryAllAddresses("s").isEmpty());
    }

    @Test
    public void registryRefreshPreservesConfiguredStaticAddress() {
        // 注册中心只替换动态地址，保留显式配置的静态地址。
        AddressPool pool = new AddressPool();
        pool.appendStaticAddress("s", this.localAddress);
        pool.refreshAddress("s", Collections.singletonList(this.remoteAddress));
        List<InterAddress> expected = Arrays.asList(this.localAddress, this.remoteAddress);
        assertEquals(expected, pool.queryAllAddresses("s"));
        assertEquals(Collections.singletonList(this.localAddress), pool.getBucket("s").getStaticAddresses());
    }

    @Test
    public void availableAddressQueryIsReadOnly() {
        // 公开地址查询不允许调用者修改结果集合。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.localAddress);
        List<InterAddress> addresses = pool.queryAvailableAddresses("s");
        assertThrows(UnsupportedOperationException.class, addresses::clear);
        assertEquals(this.localAddress, pool.nextAddress("s", "get", null));
    }

    @Test
    public void bucketNamesAreDetachedFromRegisteredServices() {
        // 修改返回的服务名称集合不能删除真实地址桶。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.localAddress);
        pool.getBucketNames().clear();
        assertNotNull(pool.getBucket("s"));
        assertEquals(this.localAddress, pool.nextAddress("s", "get", null));
    }

    @Test
    public void allAddressSnapshotCannotMutateLiveSelection() {
        // 修改完整地址快照不能改变实时查询和选址。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.localAddress);
        Map<String, List<InterAddress>> snapshot = pool.allServiceAddressToSnapshot();
        snapshot.get("s_ALL").clear();
        assertEquals(Collections.singletonList(this.localAddress), pool.queryAllAddresses("s"));
        assertEquals(this.localAddress, pool.nextAddress("s", "get", null));
    }

    @Test
    public void unitSnapshotCannotMutateLiveAddressSelection() {
        // 修改单元地址快照不能改变实时选址。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.localAddress);
        Map<String, List<InterAddress>> snapshot = pool.allServiceAddressToSnapshot();
        snapshot.get("s_UNIT").clear();
        InterAddress selected = pool.nextAddress("s", "get", null);
        assertEquals(this.localAddress, selected);
    }

    @Test(timeout = 5000)
    public void selectionRetriesQuotaCheckUntilAccepted() {
        // 前两次配额检查拒绝同一请求，第三次放行。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.localAddress);
        AtomicInteger checks = new AtomicInteger();
        pool.getBucket("s").getFlowControlRef().speedFlowControl = new SpeedFlowControl() {
            @Override
            public boolean callCheck(String service, String method, InterAddress address) {
                assertEquals("s", service);
                assertEquals("get", method);
                assertEquals(AddressPoolTest.this.localAddress, address);
                return checks.incrementAndGet() >= 3;
            }
        };

        // 选址持续检查配额，直到获得原请求的地址。
        assertEquals(this.localAddress, pool.nextAddress("s", "get", null));
        assertEquals(3, checks.get());
    }

    @Test
    public void interruptedSelectionCanBeCancelledWithoutClearingInterrupt() {
        // 已中断的调用线程发起选址。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.localAddress);
        Thread.currentThread().interrupt();
        try {
            // 调用方收到取消异常，框架保留中断标记。
            assertThrows(CancellationException.class, () -> pool.nextAddress("s", "get", null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test(timeout = 5000)
    public void waitingForQuotaObservesProviderWithdrawal() {
        // 配额检查拒绝请求时，模拟注册中心撤销该提供者。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.localAddress);
        pool.getBucket("s").getFlowControlRef().speedFlowControl = new SpeedFlowControl() {
            @Override
            public boolean callCheck(String service, String method, InterAddress address) {
                pool.removeAddress("s", AddressPoolTest.this.localAddress);
                return false;
            }
        };

        // 等待配额的选址观察到撤销结果，并结束等待。
        assertNull(pool.nextAddress("s", "get", null));
    }
}
