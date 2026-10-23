/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.annotation;
import java.lang.annotation.*;

/** The unsigned program number and nonzero unsigned version of an ONC RPC service interface. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OncRpcProgram {
    long id();

    long version() default 1L;
}