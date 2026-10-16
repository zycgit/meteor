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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class BucketTest {
    private final InterAddress localAddress  = new InterAddress("127.0.0.1", 8000, "local");
    private final InterAddress remoteAddress = new InterAddress("127.0.0.2", 8000, "remote");

    private AddressBucket populatedBucket() {
        AddressBucket bucket = new AddressBucket("s", "local");
        bucket.newAddress(Arrays.asList(this.localAddress, this.remoteAddress), AddressTypeEnum.Dynamic);
        return bucket;
    }

    @Test
    public void duplicateAppendAcrossCallsIsIdempotent() {
        // 第二次上报同一地址，保持原有地址顺序且不产生重复项。
        AddressBucket bucket = populatedBucket();
        bucket.newAddress(Collections.singletonList(this.localAddress), AddressTypeEnum.Dynamic);
        assertEquals(Arrays.asList(this.localAddress, this.remoteAddress), bucket.getAllAddresses());
    }

    @Test
    public void duplicateAddressesInSingleBatchAreDeduplicated() {
        // 同一次动态上报中的重复地址只保留一份。
        AddressBucket bucket = new AddressBucket("s", "local");
        bucket.newAddress(Arrays.asList(this.localAddress, this.localAddress), AddressTypeEnum.Dynamic);
        assertEquals(Collections.singletonList(this.localAddress), bucket.getAllAddresses());
    }

    @Test
    public void dynamicProviderCanBePromotedToStatic() {
        // 将已存在的动态地址提升为静态配置。
        AddressBucket bucket = populatedBucket();
        bucket.newAddress(Collections.singletonList(this.localAddress), AddressTypeEnum.Static);

        // 静态地址不再受失效通知影响，也不重复加入总地址集合。
        bucket.invalidAddress(this.localAddress, 60000);
        assertTrue(bucket.getAvailableAddresses().contains(this.localAddress));
        assertEquals(Collections.singletonList(this.localAddress), bucket.getStaticAddresses());
        assertEquals(Arrays.asList(this.localAddress, this.remoteAddress), bucket.getAllAddresses());
    }

    @Test
    public void unknownInvalidationDoesNotCreatePhantomAddress() {
        // 对未知地址发送失效通知，不生成地址或失效记录。
        AddressBucket bucket = new AddressBucket("s", "local");
        bucket.invalidAddress(this.localAddress, 60000);
        assertTrue(bucket.getInvalidAddresses().isEmpty());
        assertTrue(bucket.getAllAddresses().isEmpty());
    }

    @Test
    public void removingUnknownAddressLeavesBucketEmpty() {
        // 删除未知地址保持空桶，不生成任何失效记录。
        AddressBucket bucket = new AddressBucket("s", "local");
        bucket.removeAddress(this.localAddress);
        assertTrue(bucket.getInvalidAddresses().isEmpty());
        assertTrue(bucket.getAllAddresses().isEmpty());
    }

    @Test
    public void expiredInvalidationAllowsRetry() {
        // 用已到期的失效窗口准备待重试地址。
        AddressBucket bucket = populatedBucket();
        bucket.invalidAddress(this.localAddress, -1);

        // 刷新后该地址重新进入可用列表。
        bucket.refreshAddress();
        assertTrue(bucket.getAvailableAddresses().contains(this.localAddress));
        assertTrue(bucket.getAvailableAddresses().contains(this.remoteAddress));
    }

    @Test
    public void repeatedFailureImmediatelyRemovesRetriedProvider() {
        // 失效窗口到期后先恢复重试资格。
        AddressBucket bucket = populatedBucket();
        bucket.invalidAddress(this.localAddress, -1);
        bucket.refreshAddress();
        assertTrue(bucket.getAvailableAddresses().contains(this.localAddress));

        // 重试失败立即撤销资格，后续刷新也不能提前放行。
        bucket.invalidAddress(this.localAddress, 60000);
        assertFalse(bucket.getAvailableAddresses().contains(this.localAddress));
        bucket.refreshAddress();
        assertFalse(bucket.getAvailableAddresses().contains(this.localAddress));
        assertEquals(Collections.singletonList(this.remoteAddress), bucket.getAvailableAddresses());
    }

    @Test
    public void newDynamicReportClearsInvalidationAfterFailedRetry() {
        // 已到期地址重试再次失败，当前只允许无关地址被选中。
        AddressBucket bucket = populatedBucket();
        bucket.invalidAddress(this.localAddress, -1);
        bucket.refreshAddress();
        bucket.invalidAddress(this.localAddress, 60000);
        assertFalse(bucket.getAvailableAddresses().contains(this.localAddress));

        // 新的动态上报清除失败记录，并恢复该提供者。
        bucket.newAddress(Collections.singletonList(this.localAddress), AddressTypeEnum.Dynamic);
        assertFalse(bucket.getInvalidAddresses().contains(this.localAddress));
        assertEquals(Arrays.asList(this.localAddress, this.remoteAddress), bucket.getAvailableAddresses());
    }

    @Test
    public void explicitRemovalClearsDynamicInvalidation() {
        // 为目标动态地址建立失效记录。
        AddressBucket bucket = populatedBucket();
        bucket.invalidAddress(this.localAddress, 60000);

        // 显式删除同时清理地址和失效记录，保留无关地址。
        bucket.removeAddress(this.localAddress);
        assertFalse(bucket.getAllAddresses().contains(this.localAddress));
        assertFalse(bucket.getInvalidAddresses().contains(this.localAddress));
        assertEquals(Collections.singletonList(this.remoteAddress), bucket.getAvailableAddresses());
    }

    @Test
    public void explicitRemovalClearsStaticAddress() {
        // 目标地址提升为静态配置后仍可被显式删除。
        AddressBucket bucket = populatedBucket();
        bucket.newAddress(Collections.singletonList(this.localAddress), AddressTypeEnum.Static);
        bucket.removeAddress(this.localAddress);
        assertTrue(bucket.getStaticAddresses().isEmpty());
        assertEquals(Collections.singletonList(this.remoteAddress), bucket.getAllAddresses());
        assertEquals(Collections.singletonList(this.remoteAddress), bucket.getAvailableAddresses());
    }

    @Test
    public void allAddressesGetterReturnsDetachedCopy() {
        // 清空返回的总地址列表，不修改地址桶的真实内容。
        AddressBucket bucket = populatedBucket();
        bucket.getAllAddresses().clear();
        assertEquals(Arrays.asList(this.localAddress, this.remoteAddress), bucket.getAllAddresses());
    }

    @Test
    public void availableAddressesGetterReturnsDetachedCopy() {
        // 清空返回的可用列表，不改变后续的可用地址查询。
        AddressBucket bucket = populatedBucket();
        bucket.getAvailableAddresses().clear();
        assertEquals(Arrays.asList(this.localAddress, this.remoteAddress), bucket.getAvailableAddresses());
    }

    @Test
    public void invalidAddressesGetterReturnsDetachedCopy() {
        // 修改返回的失效列表，不能把正常地址写入实时失效状态。
        AddressBucket bucket = populatedBucket();
        bucket.getInvalidAddresses().add(this.localAddress);
        assertTrue(bucket.getInvalidAddresses().isEmpty());
        assertEquals(2, bucket.getAvailableAddresses().size());
    }

    @Test
    public void staticAddressesGetterReturnsDetachedCopy() {
        // 修改返回的静态列表，不能把动态提供者提升为静态配置。
        AddressBucket bucket = populatedBucket();
        bucket.getStaticAddresses().add(this.localAddress);
        assertTrue(bucket.getStaticAddresses().isEmpty());
        bucket.invalidAddress(this.localAddress, 60000);
        assertFalse(bucket.getAvailableAddresses().contains(this.localAddress));
    }

    @Test
    public void localUnitGetterDoesNotExposeMutableState() {
        // 未启用单元过滤时，修改返回的单元列表不能影响实时可用列表。
        AddressBucket bucket = populatedBucket();
        bucket.getLocalUnitAddresses().clear();
        assertEquals(2, bucket.getAvailableAddresses().size());
        assertEquals(2, bucket.getLocalUnitAddresses().size());
    }

    @Test
    public void addressChangesNotifyRegisteredObservers() {
        // 注册监听器，只观察接下来的一次地址删除。
        AddressBucket bucket = populatedBucket();
        AtomicInteger notifications = new AtomicInteger();
        bucket.addObserver((source, value) -> notifications.incrementAndGet());

        // 删除地址会发布变更通知。
        bucket.removeAddress(this.localAddress);
        assertTrue(notifications.get() > 0);
    }
}
