/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.codec;
import java.io.*;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.caucho.hessian.io.*;
import com.caucho.services.server.AbstractSkeleton;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetIgnore;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/** 为 Codec 单测准备服务元信息和独立 Caucho 报文。 */
final class HessianCodecTestFixture implements AutoCloseable {
    final MetContext     context;
    final ProtocolConfig protocol;
    final HessianCodec   codec;

    HessianCodecTestFixture() throws Exception {
        // 只构建元信息，不绑定端口或启动网络。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        configuration.connector("tcp").protocol("rsf");
        configuration.connector("http").protocol("hessian").contextPath("/rpc").addContextPath("/private");
        this.context = configuration.buildContext();
        this.context.publisher().rsfService(Methods.class, new Methods()) //
                .name("Methods").aliasName("hessian", "math").register();
        this.protocol = ProtocolConfig.newConfig("hessian", "hessian", Map.of("contextPath", "/rpc"));
        this.codec = new HessianCodec(this.protocol, this.context);
    }

    RequestPayload decode(byte[] body) throws IOException {
        return this.codec.decodeRequest(body, "/rpc/math", this.protocol.mountIndex().get("/rpc"));
    }

    RequestPayload request(String method, Class<?>[] parameterTypes, Object... values) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(7);
        request.setServiceGroup("wire");
        request.setServiceName("Methods");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod(method);
        request.setSerializeType("Hessian");
        for (int index = 0; index < parameterTypes.length; index++) {
            request.addParameter(parameterTypes[index].getName(), values[index]);
        }
        return request;
    }

    static byte[] call(int version, String method, Object... values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AbstractHessianOutput output;
        if (version == 1) {
            output = new HessianOutput(bytes);
        } else {
            output = new Hessian2Output(bytes);
        }
        output.call(method, values);
        output.flush();
        return bytes.toByteArray();
    }

    static byte[] reply(int version, Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AbstractHessianOutput output;
        if (version == 1) {
            output = new HessianOutput(bytes);
        } else {
            output = new Hessian2Output(bytes);
        }
        output.startReply();
        output.writeObject(value);
        output.completeReply();
        output.flush();
        return bytes.toByteArray();
    }

    static Object readReply(int version, byte[] body, Class<?> type) throws Throwable {
        ByteArrayInputStream bytes = new ByteArrayInputStream(body);
        if (version == 1) {
            return new HessianInput(bytes).readReply(type);
        }
        // Hessian 2 的版本头由工厂读取，reply 解码从 R/F 开始。
        bytes.skipNBytes(3);
        return new Hessian2Input(bytes).readReply(type);
    }

    static String method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return AbstractSkeleton.mangleName(Methods.class.getMethod(name, parameterTypes), false);
    }

    static ResponsePayload response(Object value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(7);
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        return response;
    }

    @Override
    public void close() throws Exception {
        this.context.close();
    }

    public static class Methods {
        public String ping() {
            return "pong";
        }

        public int subtract(int left, int right) {
            return left - right;
        }

        public String echo(String value) {
            return value;
        }

        public Object object(Object value) {
            return value;
        }

        public byte[] binary(byte[] value) {
            return value;
        }

        public Date date(Date value) {
            return value;
        }

        public Person person(Person value) {
            return value;
        }

        public List<Person> people(List<Person> values) {
            return values;
        }

        public Map<String, Object> struct(Map<String, Object> value) {
            return value;
        }

        public List<Object> values(List<Object> value) {
            return value;
        }

        public int[] integers(int[] values) {
            return values;
        }

        public String overloaded(String value) {
            return value;
        }

        public int overloaded(int value) {
            return value;
        }

        public String optional() {
            return "default";
        }

        public String optional(String value) {
            return value;
        }

        public int arity(int value) {
            return value;
        }

        public int arity(int left, int right) {
            return left + right;
        }

        public void consume(String value) {
        }

        public String readFile() throws IOException {
            throw new IOException("storage unavailable");
        }

        public String openFile() throws FileNotFoundException {
            throw new FileNotFoundException("missing.txt");
        }

        @MetIgnore
        public String ignored() {
            return "ignored";
        }

        public static String staticMethod() {
            return "static";
        }
    }

    public static class Person implements Serializable {
        private String name;
        private Person next;

        public String getName() {
            return this.name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Person getNext() {
            return this.next;
        }

        public void setNext(Person next) {
            this.next = next;
        }
    }

    public static class BusinessException extends Exception {
        private final int code;

        public BusinessException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return this.code;
        }
    }
}
