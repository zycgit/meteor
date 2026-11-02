/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.codec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.SerializerFactory;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import net.hasor.cobble.ClassUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.ref.Tuple;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;

/** 编解码 Triple 的 POJO protobuf 包装，内部值采用独立的 Hessian 2 对象文档。 */
final class TripleValueCodec {
    private static final String            SERIALIZER = "hessian4";
    private final        int               maximum;
    private final        SerializerFactory serializers;

    TripleValueCodec(int maximum, ClassLoader classLoader) {
        if (maximum <= 0) {
            throw new IllegalArgumentException("maxMessageSize must be positive");
        }
        this.maximum = maximum;
        this.serializers = new TripleHessianSerializerFactory(classLoader);
    }

    byte[] encodeArguments(Object[] values, Class<?>[] types) {
        if (values.length != types.length) {
            throw format("Triple argument values and types disagree", null);
        }

        // 每个参数分别创建 Hessian 文档，protobuf 中保留声明参数类型供重载选择。
        TripleBoundedOutputStream bytes = new TripleBoundedOutputStream(this.maximum);
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        try {
            output.writeString(1, SERIALIZER);
            for (int index = 0; index < values.length; index++) {
                output.writeByteArray(2, this.encodeValue(values[index]));
            }
            for (Class<?> type : types) {
                output.writeString(3, type.getName());
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw format("Cannot encode Triple argument wrapper", failure);
        }
    }

    Object[] decodeArguments(byte[] body, Method method) {
        return this.decodeArguments(body, method.getParameterTypes());
    }

    /** 流消息使用发布方法已经确定的元素类型，不从报文加载 Java 类型。 */
    Object[] decodeArguments(byte[] body, Class<?>[] types) {
        Tuple wrapper = this.request(body);
        List<byte[]> values = wrapper.getArg0();
        List<String> names = wrapper.getArg1();
        if (values.size() != types.length) {
            throw format("Triple argument count does not match published method", null);
        }

        // 只与已经发布的签名比较，网络提供的 argTypes 不参与类加载。
        Object[] arguments = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            if (!StringUtils.equals(names.get(index), type.getName())) {
                throw format("Triple argument type does not match published method", null);
            }
            arguments[index] = this.decodeValue(values.get(index), type);
        }
        return arguments;
    }

    List<String> argumentTypes(byte[] body) {
        Tuple wrapper = this.request(body);
        return List.copyOf(wrapper.<List<String>>getArg1());
    }

    byte[] encodeResult(Object value, Class<?> type) {
        TripleBoundedOutputStream bytes = new TripleBoundedOutputStream(this.maximum);
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        try {
            output.writeString(1, SERIALIZER);
            output.writeByteArray(2, this.encodeValue(value));
            output.writeString(3, type.getName());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw format("Cannot encode Triple result wrapper", failure);
        }
    }

    Object decodeResult(byte[] body, Class<?> type) {
        this.checkSize(body.length);
        CodedInputStream input = CodedInputStream.newInstance(body);
        String serializer = null;
        byte[] value = null;
        String name = null;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                int field = WireFormat.getTagFieldNumber(tag);
                switch (field) {
                    case 1 -> {
                        this.checkField(tag, serializer != null);
                        serializer = input.readStringRequireUtf8();
                    }
                    case 2 -> {
                        this.checkField(tag, value != null);
                        value = input.readByteArray();
                    }
                    case 3 -> {
                        this.checkField(tag, name != null);
                        name = input.readStringRequireUtf8();
                    }
                    default -> this.skip(input, tag);
                }
            }
        } catch (IOException failure) {
            throw format("Invalid Triple result wrapper", failure);
        }

        this.checkSerializer(serializer);
        if (value == null || StringUtils.isEmpty(name)) {
            throw format("Incomplete Triple result wrapper", null);
        }

        // Dubbo 的异常回复仍写原方法返回类型；异常通道通过响应头单独标记。
        boolean exception = Throwable.class.isAssignableFrom(type);
        if (!exception && !StringUtils.equals(name, type.getName())) {
            throw format("Triple result type does not match published method", null);
        }
        return this.decodeValue(value, type);
    }

    private Tuple request(byte[] body) {
        this.checkSize(body.length);
        CodedInputStream input = CodedInputStream.newInstance(body);
        String serializer = null;
        List<byte[]> values = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                int field = WireFormat.getTagFieldNumber(tag);
                switch (field) {
                    case 1 -> {
                        this.checkField(tag, serializer != null);
                        serializer = input.readStringRequireUtf8();
                    }
                    case 2 -> {
                        this.checkField(tag, false);
                        values.add(input.readByteArray());
                        this.checkArgumentCount(values.size());
                    }
                    case 3 -> {
                        this.checkField(tag, false);
                        names.add(input.readStringRequireUtf8());
                        this.checkArgumentCount(names.size());
                    }
                    default -> this.skip(input, tag);
                }
            }
        } catch (IOException failure) {
            throw format("Invalid Triple argument wrapper", failure);
        }

        this.checkSerializer(serializer);
        if (values.size() != names.size() || names.stream().anyMatch(StringUtils::isEmpty)) {
            throw format("Triple arguments and declared types disagree", null);
        }
        return Tuple.of(values, names);
    }

    private byte[] encodeValue(Object value) {
        // 写入前检查容量，不先生成超过上限的对象文档。
        TripleBoundedOutputStream bytes = new TripleBoundedOutputStream(this.maximum);
        Hessian2Output output = new Hessian2Output(bytes);
        output.setSerializerFactory(this.serializers);
        try {
            output.writeObject(value);
            output.flush();
            byte[] body = bytes.toByteArray();
            TripleHessianValueGuard.validate(body);
            return body;
        } catch (IOException failure) {
            throw format("Cannot encode Triple Hessian value", failure);
        }
    }

    private Object decodeValue(byte[] bytes, Class<?> type) {
        if (bytes.length == 0) {
            throw format("Empty Triple Hessian value", null);
        }
        try {
            // 长度和对象结构先过预检，防止原生数组解码根据伪造长度提前分配内存。
            TripleHessianValueGuard.validate(bytes);
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(bytes));
            input.setSerializerFactory(this.serializers);
            input.setRemoteResolver((name, address) -> {
                throw new IOException("Callable remote objects are not supported");
            });
            Class<?> target = type == void.class ? Object.class : type;
            Object value = input.readObject(target);
            if (input.read() != -1) {
                throw format("Unexpected trailing Triple Hessian data", null);
            }
            if (value == null && type.isPrimitive() && type != void.class) {
                throw format("Null Triple value cannot match a primitive type", null);
            }
            Class<?> expected = ClassUtils.primitiveToWrapper(type);
            if (value != null && !expected.isInstance(value)) {
                throw format("Triple Hessian value does not match published type", null);
            }
            return value;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof GrpcStatusException status) {
                throw status;
            }
            throw format("Cannot decode Triple Hessian value", failure);
        }
    }

    private void checkSize(int size) {
        if (size > this.maximum) {
            throw tooLarge();
        }
    }

    private void checkArgumentCount(int count) {
        // JVM 方法描述符最多含 255 个参数槽，限制重复空字段的膨胀。
        if (count > 255) {
            throw format("Triple argument count exceeds Java method capacity", null);
        }
    }

    private void checkField(int tag, boolean duplicate) {
        if (duplicate || WireFormat.getTagWireType(tag) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            throw format("Invalid or duplicate Triple wrapper field", null);
        }
    }

    private void skip(CodedInputStream input, int tag) throws IOException {
        if (!input.skipField(tag)) {
            throw format("Unexpected end group in Triple wrapper", null);
        }
    }

    private void checkSerializer(String serializer) {
        if (StringUtils.isEmpty(serializer)) {
            throw format("Missing Triple wrapper serializer", null);
        }
        boolean canonical = StringUtils.equals(serializer, SERIALIZER);
        boolean alias = StringUtils.equals(serializer, "hessian2");
        if (!canonical && !alias) {
            throw new GrpcStatusException(12, "Unsupported Triple wrapper serializer: " + serializer, null, null);
        }
    }

    private static GrpcStatusException tooLarge() {
        return new GrpcStatusException(8, "Triple message exceeds configured maxMessageSize", null, null);
    }

    private static GrpcStatusException format(String message, Throwable cause) {
        GrpcStatusException failure = new GrpcStatusException(13, message, null, null);
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }
}
