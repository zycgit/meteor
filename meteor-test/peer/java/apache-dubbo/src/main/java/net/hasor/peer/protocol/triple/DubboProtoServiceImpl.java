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
import org.apache.dubbo.rpc.RpcContext;

import java.util.ArrayList;
import java.util.List;

/** 使用 Dubbo 原生 StreamObserver 接口提供 Protobuf 对端。 */
public final class DubboProtoServiceImpl implements DubboProtoService {
    @Override
    public StringValue echo(StringValue value) {
        return value;
    }

    @Override
    public StringValue slow(StringValue value) {
        // 超时后仅结束本次调用，不依赖外部服务来制造等待。
        try {
            Thread.sleep(1000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
        return value;
    }

    @Override
    public StringValue fail(StringValue value) {
        throw new IllegalArgumentException("triple failure " + value.getValue());
    }

    @Override
    public StringValue metadata(StringValue value) {
        String metadata = RpcContext.getServerAttachment().getAttachment("x-triple-test");
        RpcContext.getServerResponseContext().setAttachment("x-triple-test", metadata);
        return value;
    }

    @Override
    public void count(StringValue value, StreamObserver<StringValue> response) {
        // 每条结果独立写入流，最终由完成事件结束响应方向。
        int count = Integer.parseInt(value.getValue());
        for (int index = 0; index < count; index++) {
            response.onNext(StringValue.of(String.valueOf(index)));
        }
        response.onCompleted();
    }

    @Override
    public StreamObserver<StringValue> collect(StreamObserver<StringValue> response) {
        return new StreamObserver<>() {
            private final List<String> values = new ArrayList<>();

            @Override
            public void onNext(StringValue value) {
                this.values.add(value.getValue());
            }

            @Override
            public void onError(Throwable error) {
                response.onError(error);
            }

            @Override
            public void onCompleted() {
                // 请求半关闭后产生单个聚合结果，再完成响应。
                response.onNext(StringValue.of(String.join(",", this.values)));
                response.onCompleted();
            }
        };
    }

    @Override
    public StreamObserver<StringValue> chat(StreamObserver<StringValue> response) {
        return new StreamObserver<>() {
            @Override
            public void onNext(StringValue value) {
                response.onNext(value);
            }

            @Override
            public void onError(Throwable error) {
                response.onError(error);
            }

            @Override
            public void onCompleted() {
                response.onCompleted();
            }
        };
    }
}
