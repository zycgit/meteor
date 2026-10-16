/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.grpc.web;
import java.util.concurrent.Flow;
import java.util.stream.IntStream;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.rpc.MetCaller;

/** 普通 Java 服务同时用于二进制、文本与 Protobuf 示例。 */
public class GrpcWebServiceImpl implements GrpcWebService {
    @Override
    public Message echo(Message request) {
        return request;
    }

    @Override
    public Flow.Publisher<Message> list(Message request) {
        return MetCaller.fromIterator(() -> {
            return IntStream.range(0, request.getCount()) //
                    .mapToObj(index -> Message.of(request.getValue(), index)) //
                    .iterator();
        });
    }

    @Override
    public Message fail(Message request) {
        throw new GrpcStatusException(request.getCount(), request.getValue(), null, null);
    }

    @Override
    public Flow.Publisher<Message> broken(Message request) {
        // 先交付一条有效结果，再通过流结束状态报告错误。
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    if (this.done) {
                        return;
                    }
                    this.done = true;
                    if (count <= 0) {
                        subscriber.onError(new IllegalArgumentException("demand must be positive"));
                        return;
                    }
                    subscriber.onNext(request);
                    subscriber.onError(new GrpcStatusException(9, "late failure", null, null));
                }

                @Override
                public void cancel() {
                    this.done = true;
                }
            });
        };
    }
}
