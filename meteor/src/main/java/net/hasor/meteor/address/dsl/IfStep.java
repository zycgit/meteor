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

final class IfStep implements RouteStep {
    private final RouteExpression test;
    private final List<RouteStep> steps;

    public IfStep(RouteExpression test, List<RouteStep> steps) {
        this.test = test;
        this.steps = steps;
    }

    @Override
    public Object execute(Map<String, Object> variables) {
        if (this.test.test(variables)) {
            return RouteRule.execute(this.steps, variables);
        }
        return null;
    }
}
