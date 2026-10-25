/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import io.grpc.MethodDescriptor;
import net.hasor.example.protocol.grpc.Message;

/** 官方 gRPC 对端的固定 web.Echo 线契约，不复用 Meteor 编解码器。 */
final class GrpcWebOfficialContract {
    private GrpcWebOfficialContract() {
    }

    static MethodDescriptor<Message, Message> method(String name, MethodDescriptor.MethodType type) {
        // 独立使用 Protobuf 的字段号编解码，不复用 Meteor 插件的 Codec 或会话实现。
        MethodDescriptor.Marshaller<Message> marshaller = new MethodDescriptor.Marshaller<>() {
            @Override
            public InputStream stream(Message message) {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                    if (message.getValue() != null) {
                        output.writeString(7, message.getValue());
                    }
                    output.writeInt32(2, message.getCount());
                    output.flush();
                    return new ByteArrayInputStream(bytes.toByteArray());
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }

            @Override
            public Message parse(InputStream stream) {
                try {
                    CodedInputStream input = CodedInputStream.newInstance(stream);
                    Message message = new Message();
                    int tag;
                    while ((tag = input.readTag()) != 0) {
                        if (tag == 58) {
                            message.setValue(input.readStringRequireUtf8());
                        } else if (tag == 16) {
                            message.setCount(input.readInt32());
                        } else {
                            input.skipField(tag);
                        }
                    }
                    return message;
                } catch (Exception failure) {
                    throw new IllegalArgumentException(failure);
                }
            }
        };
        return MethodDescriptor.<Message, Message>newBuilder() //
                .setType(type) //
                .setFullMethodName("web.Echo/" + name) //
                .setRequestMarshaller(marshaller) //
                .setResponseMarshaller(marshaller) //
                .build();
    }
}
