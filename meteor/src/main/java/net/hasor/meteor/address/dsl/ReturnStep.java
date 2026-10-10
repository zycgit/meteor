/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.dsl;
import java.util.List;
import java.util.Map;

final class ReturnStep implements RouteStep {
    private final RouteExpression value;

    public ReturnStep(RouteExpression value) {
        this.value = value;
    }

    @Override
    public Object execute(Map<String, Object> variables) {
        Object result = this.value.evaluate(variables);
        if (!(result instanceof List<?>)) {
            throw new IllegalArgumentException("Route return requires an address list");
        }
        return result;
    }
}
