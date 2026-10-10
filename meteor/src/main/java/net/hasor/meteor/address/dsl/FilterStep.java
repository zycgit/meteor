/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.dsl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class FilterStep implements RouteStep {
    private final String          name;
    private final String          item;
    private final RouteExpression from;
    private final RouteExpression test;

    public FilterStep(String name, String item, RouteExpression from, RouteExpression test) {
        this.name = name;
        this.item = item;
        this.from = from;
        this.test = test;
    }

    @Override
    public Object execute(Map<String, Object> variables) {
        Object source = this.from.evaluate(variables);
        if (!(source instanceof List<?> values)) {
            throw new IllegalArgumentException("Route filter requires a list");
        }
        List<Object> selected = new ArrayList<>();
        boolean existed = variables.containsKey(this.item);
        Object previous = variables.get(this.item);
        try {
            for (Object value : values) {
                variables.put(this.item, value);
                if (this.test.test(variables)) {
                    selected.add(value);
                }
            }
        } finally {
            if (existed) {
                variables.put(this.item, previous);
            } else {
                variables.remove(this.item);
            }
        }
        variables.put(this.name, selected);
        return null;
    }
}