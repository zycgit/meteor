/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.xmlrpc.codec;
import java.util.Collections;
import java.util.List;

/** Decoded XML-RPC method call; binding to Java service types belongs to the message codec. */
final class XmlRpcCall {
    private final String       methodName;
    private final List<Object> parameters;

    XmlRpcCall(String methodName, List<Object> parameters) {
        this.methodName = methodName;
        this.parameters = Collections.unmodifiableList(parameters);
    }

    String methodName() {
        return this.methodName;
    }

    List<Object> parameters() {
        return this.parameters;
    }
}
