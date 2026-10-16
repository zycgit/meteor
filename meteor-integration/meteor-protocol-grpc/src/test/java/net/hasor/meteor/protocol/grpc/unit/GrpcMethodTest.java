/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.unit;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.concurrent.Flow;
import net.hasor.meteor.protocol.grpc.codec.GrpcCodecUnitAccess.MethodShape;
import net.hasor.meteor.protocol.grpc.scenario.example.Message;
import net.hasor.meteor.protocol.grpc.scenario.example.Messages;
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
    public void rejectsInputSignature() throws Exception {
        // 取得包含InputStream 返回值的方法签名。
        Method method = Invalid.class.getMethod("input", Message.class);

        // 解析时拒绝不受支持的流签名。
        assertThrows(IllegalArgumentException.class, () -> new MethodShape(method));
    }

    @Test
    public void rejectsWriterSignature() throws Exception {
        // 取得包含Writer 返回值的方法签名。
        Method method = Invalid.class.getMethod("writer", Message.class);

        // 解析时拒绝不受支持的流签名。
        assertThrows(IllegalArgumentException.class, () -> new MethodShape(method));
    }

    @Test
    public void rejectsReadSignature() throws Exception {
        // 取得包含Reader 参数的方法签名。
        Method method = Invalid.class.getMethod("read", Reader.class);

        // 解析时拒绝不受支持的流签名。
        assertThrows(IllegalArgumentException.class, () -> new MethodShape(method));
    }

    @Test
    public void rejectsWriteSignature() throws Exception {
        // 取得包含OutputStream 参数的方法签名。
        Method method = Invalid.class.getMethod("write", OutputStream.class);

        // 解析时拒绝不受支持的流签名。
        assertThrows(IllegalArgumentException.class, () -> new MethodShape(method));
    }

    @Test
    public void rejectsRawSignature() throws Exception {
        // 取得包含缺少元素类型的 Publisher 返回值的方法签名。
        Method method = Invalid.class.getMethod("raw", Message.class);

        // 解析时拒绝不受支持的流签名。
        assertThrows(IllegalArgumentException.class, () -> new MethodShape(method));
    }

    @Test
    public void rejectsWildcardSignature() throws Exception {
        // 取得包含带通配符的 Publisher 返回值的方法签名。
        Method method = Invalid.class.getMethod("wildcard", Message.class);

        // 解析时拒绝不受支持的流签名。
        assertThrows(IllegalArgumentException.class, () -> new MethodShape(method));
    }

    @Test
    public void rejectsNestedSignature() throws Exception {
        // 取得包含嵌套 Publisher 返回值的方法签名。
        Method method = Invalid.class.getMethod("nested", Message.class);

        // 解析时拒绝不受支持的流签名。
        assertThrows(IllegalArgumentException.class, () -> new MethodShape(method));
    }

    @Test
    public void rejectsMultipleSignature() throws Exception {
        // 取得包含同一方法的两个 Publisher 参数的方法签名。
        Method method = Invalid.class.getMethod("multiple", Flow.Publisher.class, Flow.Publisher.class);

        // 解析时拒绝不受支持的流签名。
        assertThrows(IllegalArgumentException.class, () -> new MethodShape(method));
    }

    @Test
    public void resolvesListMessageDirections() throws Exception {
        // 取得服务端响应流的方法签名。
        Method method = Messages.class.getMethod("list", Message.class);

        // 解析方向与元素类型，确认只解包 Publisher 的元素。
        MethodShape shape = new MethodShape(method);
        assertFalse(shape.streamingRequest());
        assertTrue(shape.streamingResponse());
        assertSame(Message.class, shape.requestType());
        assertSame(Message.class, shape.responseType());
    }

    @Test
    public void resolvesCollectMessageDirections() throws Exception {
        // 取得客户端请求流的方法签名。
        Method method = Messages.class.getMethod("collect", Flow.Publisher.class);

        // 解析方向与元素类型，确认只解包 Publisher 的元素。
        MethodShape shape = new MethodShape(method);
        assertTrue(shape.streamingRequest());
        assertFalse(shape.streamingResponse());
        assertSame(Message.class, shape.requestType());
        assertSame(Message.class, shape.responseType());
    }

    @Test
    public void resolvesExchangeMessageDirections() throws Exception {
        // 取得双向流的方法签名。
        Method method = Messages.class.getMethod("exchange", Flow.Publisher.class);

        // 解析方向与元素类型，确认只解包 Publisher 的元素。
        MethodShape shape = new MethodShape(method);
        assertTrue(shape.streamingRequest());
        assertTrue(shape.streamingResponse());
        assertSame(Message.class, shape.requestType());
        assertSame(Message.class, shape.responseType());
    }
}
