/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.util.function.Supplier;
import net.hasor.meteor.MetFilter;

/** Supplies the ordered service filters; registration remains the host's responsibility. */
@FunctionalInterface
public interface MetFilterProvider {
    Supplier<MetFilter>[] getFilterProviders(String serviceID);
}
