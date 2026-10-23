/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.discovery;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.DirectoryQuery;
import net.hasor.meteor.registry.shared.storage.domain.RefProtocolMountData;
import net.hasor.meteor.registry.shared.storage.domain.RefServiceRelationData;

/** 协议端点的显式发现适配器；借用存储会话，仅拥有订阅和读取队列，不代发业务 RPC。 */
public final class RegistryAddressResolver implements AutoCloseable {
    private final    StorageAccess   storage;
    private final    String          serviceId;
    private final    String          protocol;
    private final    ExecutorService reader;
    private final    Cancellable     subscription;
    private volatile List<URI>       addresses = List.of();
    private volatile Exception       failure;

    public RegistryAddressResolver(StorageAccess storage, String serviceId, String protocol) {
        this.storage = storage;
        this.serviceId = serviceId;
        this.protocol = protocol;
        this.reader = Executors.newSingleThreadExecutor();
        try {
            this.subscription = storage.watchProviders(serviceId, this::changed);
        } catch (RuntimeException failure) {
            this.reader.shutdownNow();
            throw failure;
        }
        this.changed();
    }

    private void changed() {
        // 订阅线程只安排读取，避免阻塞存储提供者自己的事件线程。
        try {
            this.reader.execute(this::refresh);
        } catch (RejectedExecutionException closed) {
            // 关闭后已经进入存储通知队列的回调无需重新开启订阅。
        }
    }

    private void refresh() {
        try {
            List<URI> current = new ArrayList<>();
            DirectoryQuery query = new DirectoryQuery();
            query.setProtocol(this.protocol);
            query.setLimit(1000);
            do {
                var page = this.storage.members(this.serviceId, MetServiceType.Provider, query);
                for (RefServiceRelationData member : page.getItems()) {
                    if (!member.getService().isEnabled() || member.getInstance().isBlock()) {
                        continue;
                    }
                    for (RefProtocolMountData endpoint : member.getService().getEndpoints()) {
                        boolean selected = StringUtils.equalsIgnoreCase(this.protocol, endpoint.getProtocolName());
                        if (!selected || endpoint.getPublishedAddress() == null) {
                            continue;
                        }
                        InterAddress address = new InterAddress(endpoint.getPublishedAddress());
                        String path = endpoint.getContextPath();
                        if (StringUtils.isBlank(path)) {
                            path = "/";
                        }

                        // 新场景发布可供 SDK 直接使用的真实 scheme，不根据协议名猜测传输安全性。
                        String scheme = address.getSchema();
                        if (StringUtils.equalsIgnoreCase(scheme, "xmlrpc")) {
                            if (!StringUtils.equalsIgnoreCase(endpoint.getTransport(), "http")) {
                                throw new IllegalArgumentException("XML-RPC endpoint must use the HTTP transport");
                            }
                            scheme = "http"; // 保留最初 XML-RPC 场景的逻辑地址兼容。
                        }
                        URI target = new URI(scheme, null, address.getHost(), address.getPort(), path, null, null);
                        if (!current.contains(target)) {
                            current.add(target);
                        }
                    }
                }
                query.setCursor(page.getNextCursor());
            } while (query.getCursor() != null);
            this.addresses = List.copyOf(current);
            this.failure = null;
        } catch (Exception failure) {
            // 读取失败保留上一份地址，不能把失败伪装为提供者已退出。
            this.failure = failure;
        }
    }

    public List<URI> awaitCount(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Exception failure = this.failure;
            if (failure != null) {
                throw failure;
            }
            List<URI> current = this.addresses;
            if (current.size() == expected) {
                return current;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new TimeoutException("Registry adapter expected " + expected + " providers for " + this.serviceId);
    }

    @Override
    public void close() throws InterruptedException {
        this.subscription.cancel();
        this.reader.shutdownNow();
        if (!this.reader.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Registry resolver reader did not terminate");
        }
    }
}
