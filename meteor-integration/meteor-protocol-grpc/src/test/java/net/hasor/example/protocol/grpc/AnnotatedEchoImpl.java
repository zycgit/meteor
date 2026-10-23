/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.grpc;

public class AnnotatedEchoImpl implements AnnotatedEcho {
    @Override
    public AnnotatedMessage mirror(AnnotatedMessage input) {
        AnnotatedMessage result = new AnnotatedMessage();
        result.setValue(input.getValue());
        result.setCount(input.getCount() + 1);
        return result;
    }
}
