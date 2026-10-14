/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario.example;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.protobuf.CodedOutputStream;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.rpc.MetCaller;

public class MetadataServiceImpl implements MetadataService {
    public Message echo(Message request) {
        return request;
    }

    public Flow.Publisher<Message> list(Message request) {
        return MetCaller.fromIterable(List.of(request, request));
    }

    public Message collect(Flow.Publisher<Message> requests) {
        // 收齐客户端消息后返回汇总，保持输入完成与返回结果的先后关系。
        AtomicInteger sum = new AtomicInteger();
        try {
            var consuming = MetCaller.consume(requests, value -> sum.addAndGet(value.getCount()));
            consuming.get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        return new Message("sum", sum.get());
    }

    public Flow.Publisher<Message> exchange(Flow.Publisher<Message> requests) {
        return requests;
    }

    public Message fail(Message request) {
        // 同时返回状态详情和尾部，验证错误路径不丢失元数据。
        GrpcMetadata metadata = new GrpcMetadata();
        metadata.addTrailer("x-error", "preserved");
        int code = request.getCount();
        byte[] statusDetails = details(code);
        throw new GrpcStatusException(code, "failure 中文 + %", statusDetails, metadata);
    }

    public Flow.Publisher<Message> failStream(Message request) {
        // 在交付第一条消息后失败，模拟只能通过流尾部报告的迟到错误。
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                public void request(long count) {
                    if (this.done) {
                        return;
                    }
                    this.done = true;
                    subscriber.onNext(request);

                    // 错误详情和尾部必须随 onError 一起保留。
                    GrpcMetadata metadata = new GrpcMetadata();
                    metadata.addTrailer("x-error", "late");
                    GrpcStatusException failure = new GrpcStatusException(9, "late failure", details(9), metadata);
                    subscriber.onError(failure);
                }

                public void cancel() {
                    this.done = true;
                }
            });
        };
    }

    public static byte[] details(int code) {
        // 直接编码 google.rpc.Status 的 code 和 message，避免依赖被测编解码器。
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            CodedOutputStream output = CodedOutputStream.newInstance(buffer);
            output.writeInt32(1, code);
            output.writeString(2, "error details");
            output.flush();
            return buffer.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
