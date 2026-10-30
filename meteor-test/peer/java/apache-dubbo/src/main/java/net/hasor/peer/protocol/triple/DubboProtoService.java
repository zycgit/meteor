/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.triple;
import com.google.protobuf.StringValue;
import org.apache.dubbo.common.stream.StreamObserver;

/** 官方 Dubbo 对端使用的原生 Protobuf 服务契约。 */
public interface DubboProtoService {
    StringValue echo(StringValue value);

    StringValue slow(StringValue value);

    StringValue fail(StringValue value);

    StringValue metadata(StringValue value);

    void count(StringValue value, StreamObserver<StringValue> response);

    StreamObserver<StringValue> collect(StreamObserver<StringValue> response);

    StreamObserver<StringValue> chat(StreamObserver<StringValue> response);
}
