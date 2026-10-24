/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.connect;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import me.ivovk.connect_rpc_java.netty.ConnectNettyServerBuilder;
import me.ivovk.connect_rpc_java.netty.server.NettyServer;

/** 社区 Connect Java SDK 服务端；独立 JVM 保留其完整 Protobuf 4 运行依赖图。 */
public final class ConnectJavaServerPeer {
    private ConnectJavaServerPeer() {
    }

    public static void main(String[] arguments) throws Exception {
        // 只能使用 SDK 服务端，不能误将 Meteor 的同协议实现装入对端。
        try {
            Class.forName("net.hasor.meteor.MetContext", false, ConnectJavaServerPeer.class.getClassLoader());
            throw new IllegalStateException("Community Java SDK server must not load Meteor runtime");
        } catch (ClassNotFoundException expected) {
            System.out.println("CLASSPATH community-sdk-only");
        }
        System.out.println("PROTOBUF_SOURCE " + Struct.class.getProtectionDomain().getCodeSource().getLocation());

        // 只发布一个基础方法，JSON 字段与宿主自己的 ConnectMessage 契约一致。
        String serviceName = "meteor.connect.demo.ConnectDemoService";
        var marshaller = ProtoUtils.marshaller(Struct.getDefaultInstance());
        MethodDescriptor<Struct, Struct> method = MethodDescriptor.<Struct, Struct>newBuilder() //
                .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName(serviceName + "/echo") //
                .setRequestMarshaller(marshaller).setResponseMarshaller(marshaller).build();
        ServerServiceDefinition service = ServerServiceDefinition.builder(serviceName) //
                .addMethod(method, ServerCalls.asyncUnaryCall((request, response) -> {
                    String text = request.getFieldsOrThrow("text").getStringValue();
                    double number = request.getFieldsOrThrow("number").getNumberValue();
                    Struct result = Struct.newBuilder() //
                            .putFields("text", Value.newBuilder().setStringValue("connect-sdk:" + text).build()) //
                            .putFields("number", Value.newBuilder().setNumberValue(number + 1).build()).build();
                    response.onNext(result);
                    response.onCompleted();
                })).build();

        // SDK 构建器负责真实 HTTP 监听；本进程只拥有自己的工作线程及服务端。
        ExecutorService workers = Executors.newCachedThreadPool();
        try (NettyServer server = ConnectNettyServerBuilder.forServices(service) //
                .host("0.0.0.0").port(0).executor(workers).terminationTimeout(Duration.ofSeconds(3)).build()) {
            System.out.println("READY " + server.getPort());
            System.out.flush();
            while (System.in.read() != -1) {
                // 宿主关闭 stdin 后才能停止服务，不以定时休眠猜测调用结束。
            }
        } finally {
            workers.shutdownNow();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Connect SDK workers did not stop");
            }
        }
    }
}
