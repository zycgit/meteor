/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 可独立保存的治理策略。 */
public enum RuleKind {
    SERVICE_ROUTE,
    METHOD_ROUTE,
    ARGS_ROUTE,
    FLOW_CONTROL
}
