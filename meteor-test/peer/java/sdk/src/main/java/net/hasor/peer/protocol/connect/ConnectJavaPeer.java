/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.connect;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import com.google.protobuf.StringValue;
import kotlin.jvm.JvmClassMappingKt;
import okhttp3.OkHttpClient;

/** Java 源码直接调用官方 Connect JVM SDK；独立进程不加载 Meteor 实现。 */
public final class ConnectJavaPeer {
    private ConnectJavaPeer() {
    }

    public static void main(String[] arguments) throws Exception {
        // 对端只能加载官方 SDK；存在 Meteor 内核类时立即失败，防止假互通。
        try {
            Class.forName("net.hasor.meteor.MetContext", false, ConnectJavaPeer.class.getClassLoader());
            throw new IllegalStateException("Official Java peer must not load the Meteor runtime");
        } catch (ClassNotFoundException expected) {
            System.out.println("CLASSPATH official-sdk-only");
        }

        // 网络、序列化和 Connect/gRPC-Web 帧处理均由官方 SDK 承担。
        NetworkProtocol protocol = NetworkProtocol.valueOf(arguments[0]);
        OkHttpClient http = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
        try {
            var configuration = new ProtocolClientConfig(arguments[1], new GoogleJavaProtobufStrategy(), protocol);
            var client = new ProtocolClient(new ConnectOkHttpClient(http), configuration);
            var messageType = JvmClassMappingKt.getKotlinClass(StringValue.class);
            var method = new MethodSpec<StringValue, StringValue>(arguments[2], messageType, messageType, //
                    StreamType.UNARY, Idempotency.UNKNOWN);

            // 使用 SDK 的阻塞调用入口，不实现 HTTP 请求、帧编码或响应解析。
            ResponseMessage<StringValue> response = client.unaryBlocking( //
                    StringValue.of(arguments[3]), Collections.emptyMap(), method).execute();
            if (response instanceof ResponseMessage.Failure<StringValue> failure) {
                throw failure.getCause();
            }
            String value = ((ResponseMessage.Success<StringValue>) response).getMessage().getValue();
            String encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            System.out.println("RESULT " + encoded);
        } finally {
            // 本进程创建的 OkHttp 线程与连接由本进程关闭，不转移给测试宿主。
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
            if (http.cache() != null) {
                http.cache().close();
            }
        }
    }
}
