/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc;

import java.util.function.Supplier;
import net.hasor.rsf.RsfFilter;

/** Supplies the ordered service filters; registration remains the host's responsibility. */
@FunctionalInterface
public interface RsfFilterProvider {
    Supplier<RsfFilter>[] getFilterProviders(String serviceID);
}
