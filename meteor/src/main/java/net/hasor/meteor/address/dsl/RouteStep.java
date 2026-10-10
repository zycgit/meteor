/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.dsl;
import java.util.Map;

/** null 表示继续，非 null 表示 return 已结束整个规则。 */
interface RouteStep {
    Object execute(Map<String, Object> variables);
}
