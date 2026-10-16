/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.support.connector.protocol;
import java.io.IOException;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;

/** HTTP 传输测试使用的协议夹具。 */
public class AcknowledgementProtocol extends TextProtocol {
    public TestHttpInvocation receive(HttpRequest request) {
        TestHttpInvocation invocation = super.receive(request);
        String value = (String) invocation.request().getParameterValues().get(0);
        invocation.request().setMessage(StringUtils.equals("message", value));
        if (StringUtils.equals("broken", value)) {
            return TestHttpInvocation.dispatch(invocation.request(), response -> {
                throw new IOException("encode failed");
            });
        }
        return invocation;
    }
}
