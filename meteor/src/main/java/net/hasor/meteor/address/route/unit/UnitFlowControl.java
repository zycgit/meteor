/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.route.unit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.MatchUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.setting.Settings;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.route.AbstractRule;

/**
 * 单元流量控制规则，用来控制跨单元调用。<p>
 * <pre>例：
 * 配置实例：
 * &lt;flowControl enable="true|false" type="unit"&gt;
 *   &lt;threshold&gt;0.3&lt;/threshold&gt;
 *   &lt;exclusions&gt;172.23.*,172.19.*&lt;/exclusions&gt;
 * &lt;/flowControl&gt;
 * </pre>
 * 解释： 对某一服务，开启本单元优先调用策略
 * 但当本单元内的可用机器的数量占服务地址全部数量的比例小于0.3时，本单元优先调用策略失效，启用跨单元调用。
 * 该规则允许以下网段的服务提供者跨单元参与选址：172.23.*,172.19.*
 */
public class UnitFlowControl extends AbstractRule {
    private float        threshold;
    private List<String> exclusions;

    @Override
    public void parseControl(Settings settings) {
        this.enable(settings.getBoolean("flowControl.enable", false));
        this.threshold = settings.getFloat("flowControl.threshold", 0.0F);
        if (!Float.isFinite(this.threshold) || this.threshold < 0 || this.threshold > 1) {
            throw new IllegalArgumentException("unit threshold must be between 0 and 1");
        }

        String exclusions = settings.getString("flowControl.exclusions", "");
        this.exclusions = new ArrayList<>();
        for (String pattern : exclusions.split(",")) {
            if (!pattern.trim().isEmpty()) {
                this.exclusions.add(pattern.trim());
            }
        }
    }

    public float getThreshold() {
        return this.threshold;
    }

    public List<String> getExclusions() {
        return Collections.unmodifiableList(this.exclusions);
    }

    /**
     * 是否启用本地机房优先规则
     * @param allAmount 所有可用地址数量
     * @param localAmount 本地机房地址数量
     */
    public boolean isLocalUnit(int allAmount, int localAmount) {
        if (localAmount == 0 || !this.enable()) {
            return false;
        }
        float value = (localAmount + 0.0F) / allAmount;
        return value >= this.getThreshold();
    }

    /** 筛选本机房地址 */
    public List<InterAddress> siftUnitAddress(String unitName, List<InterAddress> address) {
        if (address == null || address.isEmpty()) {
            return null;
        }

        List<InterAddress> local = new ArrayList<>();
        List<String> exclusions = getExclusions();
        for (InterAddress inter : address) {
            boolean appendMark = false;
            //A.如果位于规则排除名单中,则直接标记appendMark为 true
            if (exclusions != null && !exclusions.isEmpty()) {
                String hostIP = inter.getHost();
                for (String ipPattern : exclusions) {
                    if (MatchUtils.matchWild(ipPattern, hostIP)) {
                        appendMark = true;
                        break;
                    }
                }
            }
            //B.如果匹配规则,则直接标记appendMark为 true
            if (!appendMark) {
                appendMark = StringUtils.equalsIgnoreCase(unitName, inter.getFormUnit());
            }

            if (appendMark) {
                local.add(inter);
            }
        }
        return local;
    }

}
