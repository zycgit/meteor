/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.connect;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import com.connectrpc.*;
import com.connectrpc.extensions.GoogleJavaJSONStrategy;
import com.connectrpc.impl.ProtocolClient;
import com.connectrpc.okhttp.ConnectOkHttpClient;
import com.connectrpc.protocols.NetworkProtocol;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import kotlin.jvm.JvmClassMappingKt;
import okhttp3.OkHttpClient;

/** Java 直接调用官方 Connect JVM SDK 的 CONNECT 模式，使用其标准 JSON 策略。 */
public final class ConnectSdkClient {
    private ConnectSdkClient() {
    }

    public static String invoke(String target, String methodName, String input) throws Exception {
        // Struct 的 JSON 映射由 Google 官方适配器完成，不手写报文或序列化器。
        OkHttpClient http = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
        try {
            var config = new ProtocolClientConfig(target, new GoogleJavaJSONStrategy(), NetworkProtocol.CONNECT);
            var client = new ProtocolClient(new ConnectOkHttpClient(http), config);
            var messageType = JvmClassMappingKt.getKotlinClass(Struct.class);
            var method = new MethodSpec<Struct, Struct>(methodName, messageType, messageType, //
                    StreamType.UNARY, Idempotency.UNKNOWN);
            Struct request = Struct.newBuilder() //
                    .putFields("text", Value.newBuilder().setStringValue(input).build()) //
                    .putFields("number", Value.newBuilder().setNumberValue(6).build()).build();

            // 帧编码、传输、状态检查和响应解码全部走 SDK 的真实调用入口。
            ResponseMessage<Struct> response = client.unaryBlocking(request, Collections.emptyMap(), method).execute();
            if (response instanceof ResponseMessage.Failure<Struct> failure) {
                throw failure.getCause();
            }
            Struct value = ((ResponseMessage.Success<Struct>) response).getMessage();
            String text = value.getFieldsOrThrow("text").getStringValue();
            int count = (int) value.getFieldsOrThrow("number").getNumberValue();
            return text + "\n" + count;
        } finally {
            // 仅关闭本次 SDK 调用创建的线程和连接，不影响其它测试。
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
        }
    }
}

