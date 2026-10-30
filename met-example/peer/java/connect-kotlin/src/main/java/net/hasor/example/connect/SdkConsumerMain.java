/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.connect;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import com.connectrpc.Idempotency;
import com.connectrpc.MethodSpec;
import com.connectrpc.ProtocolClientConfig;
import com.connectrpc.ResponseMessage;
import com.connectrpc.StreamType;
import com.connectrpc.extensions.GoogleJavaProtobufStrategy;
import com.connectrpc.impl.ProtocolClient;
import com.connectrpc.okhttp.ConnectOkHttpClient;
import com.connectrpc.protocols.NetworkProtocol;
import kotlin.jvm.JvmClassMappingKt;
import net.hasor.example.OrderContract;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderProtoUtils;
import okhttp3.OkHttpClient;

/** 官方 Connect JVM SDK 消费者，使用标准 Protobuf 契约调用 Meteor。 */
public final class SdkConsumerMain {
    private SdkConsumerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 官方 SDK 负责 Connect 请求、Protobuf 编码及 HTTP 通信。
        OkHttpClient http = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
        try {
            String address = "http://127.0.0.1:8080/connect";
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
            call(client, save, order);
            var query = OrderContract.OrderQuery.newBuilder().setId(1001).build();
            OrderExampleUtils.print(OrderProtoUtils.fromProto(call(client, find, query)));
        } finally {
            // 当前入口创建并释放自己的 HTTP 连接和执行线程。
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
        }
    }

    private static <I, O> O call(ProtocolClient client, MethodSpec<I, O> method, I request) throws Exception {
        ResponseMessage<O> response = client.unaryBlocking(request, Collections.emptyMap(), method).execute();
        if (response instanceof ResponseMessage.Failure<O> failure) {
            throw failure.getCause();
        }
        return ((ResponseMessage.Success<O>) response).getMessage();
    }
}
