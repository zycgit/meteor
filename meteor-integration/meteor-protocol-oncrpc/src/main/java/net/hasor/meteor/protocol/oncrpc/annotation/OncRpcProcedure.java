/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.annotation;
import java.lang.annotation.*;

/** An unsigned ONC RPC procedure number; zero is reserved for the built-in NULL procedure. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OncRpcProcedure {
    long id();
}
