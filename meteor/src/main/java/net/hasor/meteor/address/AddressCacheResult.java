/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.util.*;
import net.hasor.meteor.address.dsl.RouteContext;
import net.hasor.meteor.address.route.unit.UnitFlowControl;

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
        Set<String> schemes = input.getAddressSchemes();
        if (schemes != null) {
            // 先筛本次调用的协议，再计算单元优先；其它协议的本地地址不能遮蔽可用的远端地址。
            candidates = new ArrayList<>();
            for (InterAddress address : snapshot.availableAddresses) {
                if (schemes.contains(address.getSchema())) {
                    candidates.add(address);
                }
            }
            UnitFlowControl unit = snapshot.unitFlowControl;
            if (unit != null) {
                List<InterAddress> local = unit.siftUnitAddress(this.addressPool.getUnitName(), candidates);
                if (local != null && unit.isLocalUnit(candidates.size(), local.size())) {
                    candidates = local;
                }
            }
        }

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
        private final List<InterAddress>    availableAddresses;
        private final UnitFlowControl       unitFlowControl;
        private final List<InnerRuleEngine> rules;

        private BucketSnapshot(AddressBucket bucket) {
            this.addresses = bucket.getLocalUnitAddresses();
            this.availableAddresses = bucket.getAvailableAddresses();
            this.unitFlowControl = bucket.getFlowControlRef().unitFlowControl;
            RuleRef ref = bucket.getRuleRef();
            // 候选地址依次经过服务级、方法级和参数级规则，不可交换筛选层级。
            List<InnerRuleEngine> rules = new ArrayList<>(3);
            rules.add(ref.getServiceLevel());
            rules.add(ref.getMethodLevel());
            rules.add(ref.getArgsLevel());
            this.rules = List.copyOf(rules);
        }
    }
}
