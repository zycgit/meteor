/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.connect;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.connectrpc.Idempotency;
import com.connectrpc.MethodSpec;
import com.connectrpc.ProtocolClientConfig;
import com.connectrpc.ResponseMessage;
import com.connectrpc.StreamType;
import com.connectrpc.extensions.GoogleJavaProtobufStrategy;
import com.connectrpc.impl.ProtocolClient;
import com.connectrpc.okhttp.ConnectOkHttpClient;
import com.connectrpc.protocols.NetworkProtocol;
import kotlin.Unit;
import kotlin.jvm.JvmClassMappingKt;
import net.hasor.example.Order;
import net.hasor.example.OrderContract;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderProtoUtils;
import net.hasor.peer.sdk.OrderPeerSettings;
import okhttp3.OkHttpClient;

/** 官方 Connect JVM SDK 消费者，使用标准 Protobuf 契约调用 Meteor。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 官方 SDK 负责 Connect 请求、Protobuf 编码及 HTTP 通信。
        OkHttpClient http = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
        try {
            String address = OrderPeerSettings.target("http://127.0.0.1:8080/connect").toString();
            var config = new ProtocolClientConfig(address, new GoogleJavaProtobufStrategy(), NetworkProtocol.CONNECT);
            var client = new ProtocolClient(new ConnectOkHttpClient(http), config);
            var orderType = JvmClassMappingKt.getKotlinClass(OrderContract.Order.class);
            var queryType = JvmClassMappingKt.getKotlinClass(OrderContract.OrderQuery.class);
            var save = new MethodSpec<OrderContract.Order, OrderContract.Order>( //
                    OrderExampleUtils.SERVICE_NAME + "/save", orderType, orderType, //
                    StreamType.UNARY, Idempotency.UNKNOWN);
            var find = new MethodSpec<OrderContract.OrderQuery, OrderContract.Order>( //
                    OrderExampleUtils.SERVICE_NAME + "/find", queryType, orderType, //
                    StreamType.UNARY, Idempotency.UNKNOWN);

            // 先保存订单，再使用同一接口的查询方法读取并输出结果。
            var order = OrderProtoUtils.toProto(OrderExampleUtils.newOrder(1001, "connect-sdk"));
            Order saved = OrderProtoUtils.fromProto(call(client, save, order));
            var query = OrderContract.OrderQuery.newBuilder().setId(order.getId()).build();
            Order found = OrderProtoUtils.fromProto(call(client, find, query));
            OrderExampleUtils.print(saved, found);
        } finally {
            // 当前入口创建并释放自己的 HTTP 连接和执行线程。
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
        }
    }

    private static <I, O> O call(ProtocolClient client, MethodSpec<I, O> method, I request) throws Exception {
        // 两版 SDK 共用回调 API，避免 UnaryBlockingCall 在类与接口之间变化的二进制差异。
        CompletableFuture<ResponseMessage<O>> result = new CompletableFuture<>();
        var cancel = client.unary(request, Collections.emptyMap(), method, response -> {
            result.complete(response);
            return Unit.INSTANCE;
        });

        // 等待有明确上限；超时或中断时取消当前 SDK 调用，并保留最初的失败原因。
        ResponseMessage<O> response;
        try {
            response = result.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException failure) {
            try {
                cancel.invoke();
            } catch (RuntimeException | Error cancelFailure) {
                failure.addSuppressed(cancelFailure);
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw failure;
        }

        // 官方响应中的错误原样交给调用方，不以进程退出或空值替代业务结果。
        if (response instanceof ResponseMessage.Failure<O> failure) {
            throw failure.getCause();
        }
        return ((ResponseMessage.Success<O>) response).getMessage();
    }
}
