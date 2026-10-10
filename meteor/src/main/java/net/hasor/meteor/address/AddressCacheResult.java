/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.address.dsl.RouteContext;

/** 缓存地址及规则快照，路由在选址时按实际调用上下文执行。 */
class AddressCacheResult {
    private final    AddressPool                 addressPool;
    private volatile Map<String, BucketSnapshot> buckets = Map.of();

    AddressCacheResult(AddressPool addressPool) {
        this.addressPool = addressPool;
    }

    List<InterAddress> getAddressList(RouteContext input) {
        BucketSnapshot snapshot = this.buckets.get(input.getServiceId());
        if (snapshot == null) {
            return null;
        }
        List<InterAddress> candidates = snapshot.addresses;
        // 服务、方法、参数规则依次筛选，空列表不会回退并绕过治理。
        for (InnerRuleEngine rule : snapshot.rules) {
            if (candidates.isEmpty()) {
                break;
            }
            if (rule.isEnable()) {
                candidates = rule.evaluate(input, candidates);
            }
        }
        return candidates;
    }

    synchronized void reset() {
        // 只串行化快照发布，不在等待单个地址本时持有整个地址池的锁。
        Map<String, BucketSnapshot> updated = new HashMap<>();
        for (Map.Entry<String, AddressBucket> entry : this.addressPool.addressPool.entrySet()) {
            AddressBucket bucket = entry.getValue();
            synchronized (bucket) {
                updated.put(entry.getKey(), new BucketSnapshot(bucket));
            }
        }
        this.buckets = updated;
    }

    private static final class BucketSnapshot {
        private final List<InterAddress>    addresses;
        private final List<InnerRuleEngine> rules;

        private BucketSnapshot(AddressBucket bucket) {
            this.addresses = bucket.getLocalUnitAddresses();
            RuleRef ref = bucket.getRuleRef();
            this.rules = List.of(ref.getServiceLevel(), ref.getMethodLevel(), ref.getArgsLevel());
        }
    }
}
