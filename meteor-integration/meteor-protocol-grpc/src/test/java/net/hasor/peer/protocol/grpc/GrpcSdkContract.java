/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.grpc;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;

/** 纯官方 Protobuf/gRPC 契约，与插件已有的 proto/message.proto 和 Echo 服务保持一致。 */
public final class GrpcSdkContract {
    private final Descriptors.Descriptor                           type;
    private final MethodDescriptor<DynamicMessage, DynamicMessage> method;

    public GrpcSdkContract() throws Descriptors.DescriptorValidationException {
        // 只声明契约，字段 value=7、count=2；线上编解码完全由官方 SDK 完成。
        DescriptorProto message = DescriptorProto.newBuilder().setName("Message") //
                .addField(FieldDescriptorProto.newBuilder().setName("value").setNumber(7) //
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)) //
                .addField(FieldDescriptorProto.newBuilder().setName("count").setNumber(2) //
                        .setType(FieldDescriptorProto.Type.TYPE_INT32)).build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder().setName("message.proto") //
                .setPackage("example").setSyntax("proto3").addMessageType(message).build();
        Descriptors.FileDescriptor descriptor = Descriptors.FileDescriptor.buildFrom(file, //
                new Descriptors.FileDescriptor[0]);
        this.type = descriptor.findMessageTypeByName("Message");
        var marshaller = ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(this.type));
        this.method = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder() //
                .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("example.Echo/echo") //
                .setRequestMarshaller(marshaller).setResponseMarshaller(marshaller).build();
    }

    public MethodDescriptor<DynamicMessage, DynamicMessage> method() {
        return this.method;
    }

    public DynamicMessage message(String value, int count) {
        return DynamicMessage.newBuilder(this.type) //
                .setField(this.type.findFieldByName("value"), value) //
                .setField(this.type.findFieldByName("count"), count).build();
    }

    public ServerServiceDefinition service(String prefix) {
        // 专属前缀与递增值证明实际执行了 SDK 服务，不以原样回显冒充反向互通。
        return ServerServiceDefinition.builder("example.Echo") //
                .addMethod(this.method, ServerCalls.asyncUnaryCall((request, observer) -> {
                    String text = (String) request.getField(this.type.findFieldByName("value"));
                    int count = (Integer) request.getField(this.type.findFieldByName("count"));
                    observer.onNext(message(prefix + text, count + 1));
                    observer.onCompleted();
                })).build();
    }
}
