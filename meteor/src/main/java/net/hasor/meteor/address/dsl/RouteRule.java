/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.dsl;
import java.util.*;
import net.hasor.meteor.address.InterAddress;

/** 解析完成的 XML 路由，可由多个调用并行执行。 */
public final class RouteRule {
    private static final RouteUtils      UTILS = new RouteUtils();
    private final        List<RouteStep> steps;

    public RouteRule(List<RouteStep> steps) {
        this.steps = steps;
    }

    public List<InterAddress> evaluate(RouteContext input, List<InterAddress> addresses) {
        // 每次调用独立绑定变量，禁止规则替换框架输入。
        Map<String, Object> variables = new HashMap<>();
        variables.put("env", Collections.unmodifiableMap(input.getEnv()));
        variables.put("service", Collections.unmodifiableMap(input.getService()));
        variables.put("method", Collections.unmodifiableMap(input.getMethod()));
        variables.put("args", input.getArgs());
        variables.put("addresses", Collections.unmodifiableList(addresses));
        variables.put("utils", UTILS);
        Object result = execute(this.steps, variables);
        if (result == null) {
            throw new IllegalArgumentException("Route finished without return");
        }

        // 路由只能选择当前候选地址，保留顺序并去重。
        Set<InterAddress> available = new HashSet<>(addresses);
        Set<InterAddress> selected = new LinkedHashSet<>();
        for (Object value : (List<?>) result) {
            if (!(value instanceof InterAddress address) || !available.contains(address)) {
                throw new IllegalArgumentException("Route returned an address outside its candidates");
            }
            selected.add(address);
        }
        return new ArrayList<>(selected);
    }

    static Object execute(List<RouteStep> steps, Map<String, Object> variables) {
        for (RouteStep step : steps) {
            Object result = step.execute(variables);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
