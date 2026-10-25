/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.discovery;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.InterAddress;

/** 基础互通场景在地址可见后，继续观察正式选址快照就绪，不发送探测 RPC。 */
public final class AddressSelectionAwaiter {
    private AddressSelectionAwaiter() {
    }

    public static void await(MetContext context, MetBindInfo<?> service, String methodName) //
            throws InterruptedException, TimeoutException {
        // bootstrap 的公开更新器使用地址池；不读取私有字段或绕过真实选址实现。
        MetUpdater updater = context.getUpdater();
        if (!(updater instanceof AddressPool pool)) {
            throw new AssertionError("Bootstrap updater does not expose the public address pool");
        }
        String serviceId = service.getBindID();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        // 这些基础场景未安装业务路由或流控；只观察可见地址是否已进入正式选址快照。
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while waiting for address selection");
            }
            List<InterAddress> available = updater.queryAvailableAddresses(serviceId);
            InterAddress selected = pool.nextAddress(serviceId, methodName, new Object[0]);
            if (selected != null && available.contains(selected)) {
                return;
            }

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Discovered address did not become selectable: " + serviceId);
            }
            long pause = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10));
            LockSupport.parkNanos(pause);
        }
    }
}
