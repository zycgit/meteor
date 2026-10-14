/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.address.route;

/** 流控测试共用的无状态 XML 数据准备。 */
public final class FlowControlScripts {
    private FlowControlScripts() {
    }

    public static String unit(boolean enabled, double threshold, String exclusions) {
        return """
                <flowControl type="unit" enable="%s">
                    <threshold>%s</threshold>
                    <exclusions>%s</exclusions>
                </flowControl>
                """.formatted(enabled, threshold, exclusions);
    }

    public static String speed(String action, boolean enabled, int rate, int peak, int window) {
        return """
                <flowControl type="speed" enable="%s">
                    <action>%s</action>
                    <rate>%s</rate>
                    <peak>%s</peak>
                    <timeWindow>%s</timeWindow>
                </flowControl>
                """.formatted(enabled, action, rate, peak, window);
    }

}
