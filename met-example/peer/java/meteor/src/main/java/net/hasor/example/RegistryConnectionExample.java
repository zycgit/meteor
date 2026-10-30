/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;

/** 等待注册中心的自动发现结果。 */
public final class RegistryConnectionExample {
    private RegistryConnectionExample() {
    }

    public static void awaitDiscovery(MetContext context, MetBindInfo<?> service) throws Exception {
        // 等待订阅结果进入地址本，不用业务请求探测提供者。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<InterAddress> addresses = context.getUpdater().queryAvailableAddresses(service.getBindID());
            if (addresses != null && !addresses.isEmpty()) {
                System.out.println("Discovered addresses: " + addresses);
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new TimeoutException("No provider discovered for " + service.getBindID());
    }
}
