/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.dsl;
import java.util.Map;

final class BindStep implements RouteStep {
    private final String          name;
    private final RouteExpression value;

    BindStep(String name, RouteExpression value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public Object execute(Map<String, Object> variables) {
        variables.put(this.name, this.value.evaluate(variables));
        return null;
    }
}
