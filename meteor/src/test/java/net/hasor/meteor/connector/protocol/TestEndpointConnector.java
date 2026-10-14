/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import net.hasor.meteor.connector.MetConnector;

/** 仅向测试暴露包内端点类型判断，不修改生产实现的可见性。 */
public final class TestEndpointConnector {
    private TestEndpointConnector() {
    }

    public static boolean isEndpoint(MetConnector connector) {
        return connector instanceof EndpointConnector<?>;
    }
}
