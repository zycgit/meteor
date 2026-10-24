/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.hasor.cobble.StringUtils;

/** 每种模式单独生成 JUnit 实例，注册模式不得向消费者提供业务地址。 */
public enum OrderDiscovery {
    DIRECT,
    JDBC,
    ZOOKEEPER,
    NACOS;

    public static List<OrderDiscovery> selected() {
        // 每个 token 都先校验；通配符不能掩盖同时传入的拼写错误。
        String property = "meteor.matrix.discoveries";
        String filter = System.getProperty(property, "*");
        List<String> names = new ArrayList<>();
        boolean all = false;
        for (String token : filter.split(",", -1)) {
            String name = token.trim().toLowerCase(Locale.ROOT);
            if (StringUtils.equals(name, "*") || StringUtils.equals(name, "all")) {
                all = true;
                continue;
            }
            boolean known = false;
            for (OrderDiscovery discovery : values()) {
                if (StringUtils.equals(name, discovery.toString())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new IllegalArgumentException("Unknown discovery mode in " + property + ": " + token);
            }
            names.add(name);
        }

        // 保持枚举顺序和去重行为，使同一范围生成稳定的 JUnit 参数集合。
        List<OrderDiscovery> selected = new ArrayList<>();
        for (OrderDiscovery discovery : values()) {
            if (all || names.contains(discovery.toString())) {
                selected.add(discovery);
            }
        }
        return selected;
    }

    @Override
    public String toString() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
