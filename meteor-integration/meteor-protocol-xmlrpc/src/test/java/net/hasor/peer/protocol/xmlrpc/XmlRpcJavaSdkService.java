/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.xmlrpc;

/** Apache 根据公开方法反射分派的普通 Java 业务实现。 */
public final class XmlRpcJavaSdkService {
    public String echo(String value) {
        return "sdk-server:" + value;
    }
}