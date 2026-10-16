/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.codec;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectDemoServiceImpl;
import net.hasor.example.protocol.connect.ConnectMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.connect.ConnectStatusException;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectProtoMappingTest {
    @Test
    public void protobufEncodingUsesTheBoundFieldNumbers() throws Exception {
        // 同一个 Java 对象按照绑定的 proto 字段编号编码。
        try (MetContext context = this.configuration().buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<?> service = this.publish(context, resource);
            ConnectHttpCodec codec = this.codec(context);
            RequestPayload request = this.payload(service, "Protobuf");
            byte[] encoded = codec.encodeValue(request, ConnectMessage.of("proto", 7), true);

            // 用标准 Protobuf 描述独立解析正文。
            Descriptor descriptor = resource.method(this.alias(), "echo").getInputType();
            DynamicMessage message = DynamicMessage.parseFrom(descriptor, encoded);
            assertEquals("proto", message.getField(descriptor.findFieldByNumber(1)));
            assertEquals(7, message.getField(descriptor.findFieldByNumber(2)));
        }
    }

    @Test
    public void protobufDecodingRestoresTheJavaDto() throws Exception {
        // 标准描述构造消息，而不是依靠本模块编码器生成输入。
        try (MetContext context = this.configuration().buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<?> service = this.publish(context, resource);
            Descriptor descriptor = resource.method(this.alias(), "echo").getInputType();
            byte[] body = DynamicMessage.newBuilder(descriptor) //
                    .setField(descriptor.findFieldByNumber(1), "decoded") //
                    .setField(descriptor.findFieldByNumber(2), 9).build().toByteArray();

            // 解码后的类型与接口直接参数类型一致。
            RequestPayload request = this.payload(service, "Protobuf");
            ConnectMessage message = (ConnectMessage) this.codec(context) //
                    .decodeValue(request, ConnectMessage.class, body, true);
            assertEquals("decoded", message.getText());
            assertEquals(9, message.getNumber());
        }
    }

    @Test
    public void emptyProtobufBodyRepresentsTheDefaultMessage() throws Exception {
        // proto3 的空正文是合法默认消息。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context, this.resource());
            RequestPayload request = this.payload(service, "Protobuf");
            ConnectMessage message = (ConnectMessage) this.codec(context) //
                    .decodeValue(request, ConnectMessage.class, new byte[0], true);
            assertNull(message.getText());
            assertEquals(0, message.getNumber());
        }
    }

    @Test
    public void acceptsDynamicMessageAsAProtobufValue() throws Exception {
        // 调用方可直接传递与方法描述一致的 DynamicMessage。
        try (MetContext context = this.configuration().buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<?> service = this.publish(context, resource);
            Descriptor descriptor = resource.method(this.alias(), "echo").getInputType();
            DynamicMessage message = DynamicMessage.newBuilder(descriptor) //
                    .setField(descriptor.findFieldByNumber(1), "dynamic").build();
            byte[] encoded = this.codec(context).encodeValue(this.payload(service, "Protobuf"), message, true);
            assertArrayEquals(message.toByteArray(), encoded);
        }
    }

    @Test
    public void decodesDirectlyToDynamicMessage() throws Exception {
        // 方法资源确定消息描述，目标类型明确选择 DynamicMessage。
        try (MetContext context = this.configuration().buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<?> service = this.publish(context, resource);
            Descriptor descriptor = resource.method(this.alias(), "echo").getInputType();
            byte[] bytes = DynamicMessage.newBuilder(descriptor) //
                    .setField(descriptor.findFieldByNumber(1), "dynamic").build().toByteArray();
            Object decoded = this.codec(context).decodeValue(this.payload(service, "Protobuf"), //
                    DynamicMessage.class, bytes, true);
            assertTrue(decoded instanceof DynamicMessage);
            assertEquals(descriptor, ((DynamicMessage) decoded).getDescriptorForType());
        }
    }

    @Test
    public void rejectsADynamicMessageFromADifferentType() throws Exception {
        // 输入类型与输出类型字段相似，但完整类型名不同。
        try (MetContext context = this.configuration().buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<?> service = this.publish(context, resource);
            Descriptor output = resource.method(this.alias(), "echo").getOutputType();
            DynamicMessage message = DynamicMessage.newBuilder(output).build();

            // 不能仅凭相同字段字节接受错误消息类型。
            RequestPayload request = this.payload(service, "Protobuf");
            assertThrows(ConnectStatusException.class, () -> this.codec(context).encodeValue(request, message, true));
        }
    }

    @Test
    public void protobufServiceAlsoAcceptsProtoJson() throws Exception {
        // Connect 允许同一份 proto 描述同时提供二进制和 JSON 编码。
        try (MetContext context = this.configuration().buildContext()) {
            GrpcProtoResource resource = this.resource();
            context.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", this.alias()).serialize("Protobuf").bindResource(resource).register();
            byte[] body = "{\"text\":\"json\",\"number\":3}".getBytes(StandardCharsets.UTF_8);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            String path = "/" + this.alias() + "/echo";
            HttpRequest input = HttpRequest.newBuffered("POST", path, headers, body, 2, Map.of());

            // 服务默认编码不会禁止对端选择规范定义的另一种编码。
            ConnectHttpCodec codec = this.codec(context);
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals("Json", request.getSerializeType());
            assertEquals("json", ((ConnectMessage) request.getParameterValues().get(0)).getText());
        }
    }

    @Test
    public void boundJsonRejectsUnknownProtoFields() throws Exception {
        // JSON 绑定 proto 后按照 ProtoJSON 规则解析，而不是忽略未知字段。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context, this.resource());
            RequestPayload request = this.payload(service, "Json");
            byte[] json = "{\"unknown\":1}".getBytes(StandardCharsets.UTF_8);
            ConnectHttpCodec codec = this.codec(context);
            assertThrows(IOException.class, () -> codec.decodeValue(request, ConnectMessage.class, json, true));
        }
    }

    @Test
    public void boundJsonRejectsMalformedUtf8() throws Exception {
        // 无法组成 UTF-8 的正文不能先替换字符再交给 JSON 解析。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context, this.resource());
            RequestPayload request = this.payload(service, "Json");
            byte[] invalid = { '{', '"', 't', 'e', 'x', 't', '"', ':', '"', (byte) 0xC3, '"', '}' };
            ConnectHttpCodec codec = this.codec(context);
            assertThrows(CharacterCodingException.class, () -> {
                codec.decodeValue(request, ConnectMessage.class, invalid, true);
            });
        }
    }

    @Test
    public void protobufDoesNotFallBackWithoutAResource() throws Exception {
        // JSON 订阅声明合法，但单次选择 Protobuf 时仍不能在缺少资源的情况下回退。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", this.alias()).serialize("Json").register();
            RequestPayload request = this.payload(service, "Protobuf");
            ConnectStatusException failure = assertThrows(ConnectStatusException.class, //
                    () -> this.codec(context).encodeValue(request, ConnectMessage.of("value", 1), true));
            assertEquals("Protobuf service must bind proto file.", failure.getDescription());
        }
    }

    @Test
    public void jsonWithoutAResourceUsesTheOrdinaryDtoShape() throws Exception {
        // 普通 Java JSON 服务不要求提供 proto 文件。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", this.alias()).serialize("Json").register();
            RequestPayload request = this.payload(service, "Json");
            byte[] json = this.codec(context).encodeValue(request, ConnectMessage.of("plain", 3), true);

            // 编码与解码均沿用直接 DTO，而不是数组包装请求。
            Object decoded = this.codec(context).decodeValue(request, ConnectMessage.class, json, true);
            assertEquals("plain", ((ConnectMessage) decoded).getText());
        }
    }

    @Test
    public void unboundJsonRejectsTrailingDocuments() throws Exception {
        // 单个 JSON RPC 消息不能在第一份对象后拼接第二份文档。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", this.alias()).serialize("Json").register();
            RequestPayload request = this.payload(service, "Json");
            byte[] body = "{\"text\":\"first\"} {\"text\":\"second\"}".getBytes(StandardCharsets.UTF_8);
            ConnectHttpCodec codec = this.codec(context);

            // 尾部数据必须报告为无效输入。
            assertThrows(IOException.class, () -> codec.decodeValue(request, ConnectMessage.class, body, true));
        }
    }

    @Test
    public void boundJsonRejectsTrailingDocuments() throws Exception {
        // ProtoJSON 同样只接受一个消息文档。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context, this.resource());
            RequestPayload request = this.payload(service, "Json");
            byte[] body = "{} {}".getBytes(StandardCharsets.UTF_8);
            ConnectHttpCodec codec = this.codec(context);
            assertThrows(IOException.class, () -> codec.decodeValue(request, ConnectMessage.class, body, true));
        }
    }

    @Test
    public void unboundJsonRejectsDuplicateKeys() throws Exception {
        // 同名属性不能在一个请求中给出两个互相冲突的值。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(ConnectDemoService.class) //
                    .aliasName("connect", this.alias()).serialize("Json").register();
            RequestPayload request = this.payload(service, "Json");
            byte[] body = "{\"text\":\"first\",\"text\":\"second\"}".getBytes(StandardCharsets.UTF_8);
            ConnectHttpCodec codec = this.codec(context);
            assertThrows(IOException.class, () -> codec.decodeValue(request, ConnectMessage.class, body, true));
        }
    }

    @Test
    public void rejectsResponseCodingDifferentFromTheRequest() throws Exception {
        // 请求选择 JSON，对端却声明二进制 Protobuf。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context, this.resource());
            RequestPayload request = this.payload(service, "Json");
            HttpResponse response = HttpResponse.newBuffered(200, //
                    Map.of("content-type", List.of("application/proto")), new byte[0], Map.of());
            ConnectStatusException failure = assertThrows(ConnectStatusException.class, //
                    () -> this.codec(context).decode(request, response));
            assertEquals("internal", failure.getCode());
        }
    }

    @Test
    public void protoJsonPrintsInt64AsADecimalString() throws Exception {
        // 准备含 int64 字段的描述，JSON 表示必须保留精度。
        try (MetContext context = this.configuration().buildContext()) {
            GrpcProtoResource resource = this.numericResource();
            MetBindInfo<?> service = context.publisher().rsfService(NumericService.class) //
                    .aliasName("connect", "numeric.Service").serialize("Json").bindResource(resource).register();
            RequestPayload request = this.payload(service, "Json");
            NumericMessage value = new NumericMessage();
            value.setNumber(9007199254740993L);

            // 大于 JavaScript 安全整数范围时，线上仍使用带引号的十进制文本。
            String json = new String(this.codec(context).encodeValue(request, value, true), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"9007199254740993\""));
        }
    }

    @Test
    public void protoJsonReadsInt64FromADecimalString() throws Exception {
        // 对端遵循 ProtoJSON，用字符串发送 int64。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(NumericService.class) //
                    .aliasName("connect", "numeric.Service").serialize("Json") //
                    .bindResource(this.numericResource()).register();
            RequestPayload request = this.payload(service, "Json");
            byte[] json = "{\"number\":\"9007199254740993\"}".getBytes(StandardCharsets.UTF_8);

            // Java DTO 的 long 不丢失整数精度。
            NumericMessage decoded = (NumericMessage) this.codec(context) //
                    .decodeValue(request, NumericMessage.class, json, true);
            assertEquals(9007199254740993L, decoded.getNumber());
        }
    }

    @Test
    public void validatesAJsonServiceWithoutProto() {
        // 发布校验直接接收服务声明，不要求 proto 或运行中的上下文。
        ServiceDomain<ConnectDemoService> service = new ServiceDomain<>(ConnectDemoService.class);
        service.putAliasName("connect", this.alias());
        service.setSerializeType("Json");
        ConnectHttpCodec.validateService(this.protocol(), service);
    }

    @Test
    public void publicationRejectsProtobufWithoutProto() {
        // Protobuf 服务在发布校验阶段就必须具备绑定描述。
        ServiceDomain<ConnectDemoService> service = new ServiceDomain<>(ConnectDemoService.class);
        service.putAliasName("connect", this.alias());
        service.setSerializeType("Protobuf");
        ProtocolConfig protocol = this.protocol();
        assertThrows(IllegalArgumentException.class, () -> ConnectHttpCodec.validateService(protocol, service));
    }

    @Test
    public void publicationRejectsUnsupportedSerialization() {
        // Connect 仅接受 Json 或 Protobuf。
        ServiceDomain<ConnectDemoService> service = new ServiceDomain<>(ConnectDemoService.class);
        service.putAliasName("connect", this.alias());
        service.setSerializeType("Java");
        ProtocolConfig protocol = this.protocol();
        assertThrows(IllegalArgumentException.class, () -> ConnectHttpCodec.validateService(protocol, service));
    }

    @Test
    public void publicationRejectsOverloadedMethods() {
        // Connect 过程地址只有方法名，不能无歧义表示 Java 重载。
        ServiceDomain<OverloadedService> service = new ServiceDomain<>(OverloadedService.class);
        service.putAliasName("connect", "example.Overloaded");
        service.setSerializeType("Json");
        ProtocolConfig protocol = this.protocol();
        assertThrows(IllegalArgumentException.class, () -> ConnectHttpCodec.validateService(protocol, service));
    }

    @Test
    public void publicationRejectsMessageMethods() {
        // 方法级消息模式没有响应，不属于 Connect 的四种 RPC 形态。
        ServiceDomain<MessageService> service = new ServiceDomain<>(MessageService.class);
        service.putAliasName("connect", "example.Message");
        service.setSerializeType("Json");
        ProtocolConfig protocol = this.protocol();
        assertThrows(IllegalArgumentException.class, () -> ConnectHttpCodec.validateService(protocol, service));
    }

    @Test
    public void publicationRejectsMultipleRequestArguments() {
        // 直接请求消息只能是一个 DTO。
        ServiceDomain<MultipleArgumentsService> service = new ServiceDomain<>(MultipleArgumentsService.class);
        service.putAliasName("connect", "example.Multiple");
        service.setSerializeType("Json");
        ProtocolConfig protocol = this.protocol();
        assertThrows(IllegalArgumentException.class, () -> ConnectHttpCodec.validateService(protocol, service));
    }

    @Test
    public void publicationRejectsFlowShapeDifferentFromProto() throws Exception {
        // proto 声明普通 echo，Java 却把请求定义为 Publisher。
        ServiceDomain<WrongFlowService> service = new ServiceDomain<>(WrongFlowService.class);
        service.putAliasName("connect", this.alias());
        service.setSerializeType("Json");
        service.setBindResource(this.resource());
        ProtocolConfig protocol = this.protocol();
        assertThrows(IllegalArgumentException.class, () -> ConnectHttpCodec.validateService(protocol, service));
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultProtocol("connect");
        configuration.connector("http").option("httpVersion", 2).protocol("connect");
        return configuration;
    }

    private MetBindInfo<?> publish(MetContext context, GrpcProtoResource resource) {
        return context.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                .aliasName("connect", this.alias()).serialize("Json").bindResource(resource).register();
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
    }

    private GrpcProtoResource numericResource() throws Exception {
        // 此资源只用于同包映射单测，不进入插件产物。
        String schema = "syntax = \"proto3\"; package numeric; " //
                + "message Value { int64 number = 1; } " //
                + "service Service { rpc echo(Value) returns (Value); }";
        Path file = Files.createTempFile("connect-numeric-", ".proto");
        try {
            Files.writeString(file, schema);
            return new GrpcProtoResource(file.toUri().toURL());
        } finally {
            Files.delete(file);
        }
    }

    private ConnectHttpCodec codec(MetContext context) {
        return new ConnectHttpCodec(this.protocol(), context);
    }

    private ProtocolConfig protocol() {
        return ProtocolConfig.newConfig("connect", "connect", Map.of("contextPath", "/", "httpVersion", "2"));
    }

    private String alias() {
        return "meteor.connect.demo.ConnectDemoService";
    }

    private RequestPayload payload(MetBindInfo<?> service, String coding) {
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod("echo");
        request.setSerializeType(coding);
        return request;
    }

    public interface NumericService {
        NumericMessage echo(NumericMessage value);
    }

    public interface OverloadedService {
        ConnectMessage echo(ConnectMessage value);

        ConnectMessage echo(String value);
    }

    public interface MessageService {
        @MetMessage
        ConnectMessage echo(ConnectMessage value);
    }

    public interface MultipleArgumentsService {
        ConnectMessage echo(ConnectMessage first, ConnectMessage second);
    }

    public interface WrongFlowService {
        ConnectMessage echo(Flow.Publisher<ConnectMessage> values);
    }

    public static class NumericMessage {
        private long number;

        public long getNumber() {
            return this.number;
        }

        public void setNumber(long number) {
            this.number = number;
        }
    }
}
