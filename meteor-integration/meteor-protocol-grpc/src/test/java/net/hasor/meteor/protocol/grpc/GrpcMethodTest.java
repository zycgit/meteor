/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.concurrent.Flow;
import net.hasor.meteor.protocol.grpc.GrpcInteropTest.Message;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcMethodTest {
    interface Invalid {
        InputStream input(Message request);

        Writer writer(Message request);

        Message read(Reader source);

        Message write(OutputStream target);

        Flow.Publisher raw(Message request);

        Flow.Publisher<?> wildcard(Message request);

        Flow.Publisher<Flow.Publisher<Message>> nested(Message request);

        Message multiple(Flow.Publisher<Message> first, Flow.Publisher<Message> second);
    }

    @Test
    public void ioObjectsAndAmbiguousFlowSignaturesAreRejected() {
        for (Method method : Invalid.class.getMethods()) {
            assertThrows(method.toString(), IllegalArgumentException.class, () -> new GrpcMethod(method));
        }
    }

    @Test
    public void flowElementTypesAndDirectionsAreResolvedFromTheInterface() throws Exception {
        Class<GrpcFlowTest.Messages> type = GrpcFlowTest.Messages.class;
        GrpcMethod list = new GrpcMethod(type.getMethod("list", Message.class));
        assertFalse(list.streamingRequest());
        assertTrue(list.streamingResponse());
        assertSame(Message.class, list.requestType());
        assertSame(Message.class, list.responseType());
        GrpcMethod collect = new GrpcMethod(type.getMethod("collect", Flow.Publisher.class));
        assertTrue(collect.streamingRequest());
        assertFalse(collect.streamingResponse());
        GrpcMethod exchange = new GrpcMethod(type.getMethod("exchange", Flow.Publisher.class));
        assertTrue(exchange.streamingRequest());
        assertTrue(exchange.streamingResponse());
    }
}
