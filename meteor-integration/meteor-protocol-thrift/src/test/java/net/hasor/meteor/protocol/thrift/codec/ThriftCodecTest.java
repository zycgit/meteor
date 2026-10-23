/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.DeclaredFailure;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.example.protocol.thrift.generated.InteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import net.hasor.meteor.protocol.thrift.ThriftProtocol;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ThriftCodecTest {
    private final ThriftWireCodec                wire = new ThriftWireCodec(1024 * 1024, 1000, 32);
    private       MetContext                     context;
    private       MetBindInfo<ThriftDemoService> service;
    private       ProtocolConfig                 protocol;
    private       ThriftCodec                    codec;
    private       RouteMountIndex                routes;

    @Before
    public void preparePublishedServiceMetadata() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift").setDefaultGroup("wire");
        configuration.connector("tcp").protocol("thrift");
        this.context = configuration.buildContext();
        this.service = this.context.publisher() //
                .rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                .name("Interop").aliasName("thrift", "interop").register();
        this.protocol = ProtocolConfig.newConfig("thrift", "thrift", Map.of());
        this.routes = new RouteMountIndex("tcp-0", this.protocol);
        this.codec = new ThriftCodec(this.protocol, this.routes, this.context);
    }

    @After
    public void closeContext() throws Exception {
        if (this.context != null) {
            this.context.close();
        }
    }

    @Test
    public void encodedArgumentsFollowDeclarationOrderInsteadOfFieldIds() throws Exception {
        // add 的声明顺序是 left、right，字段编号却分别是 7、2。
        RequestPayload request = this.request("add");
        request.addParameter("int", 19);
        request.addParameter("int", 4);

        // SDK 读取编码结果，不能因按编号排序而交换两个同类型参数。
        ThriftMessage message = this.wire.decode(this.codec.encodeRequest(request, 11));
        InteropService.add_args arguments = this.readBinaryBody(message, new InteropService.add_args());
        assertEquals(19, arguments.getLeft());
        assertEquals(4, arguments.getRight());
        assertEquals(TMessageType.CALL, message.messageType());
        assertEquals(11, message.sequenceId());
    }

    @Test
    public void decodedArgumentsFollowDeclarationOrderInsteadOfFieldIds() throws Exception {
        // 对端使用标准 SDK 写出字段编号不递增的参数。
        ThriftMessage message = this.message("add", TMessageType.CALL, new InteropService.add_args(19, 4));
        RequestPayload request = this.codec.decodeRequest(message);

        // Dispatcher 接收的类型和值与 Java 方法声明顺序一致。
        assertEquals(List.of("int", "int"), request.getParameterTypes());
        assertEquals(List.of(19, 4), request.getParameterValues());
        assertEquals("wire", request.getServiceGroup());
        assertEquals("Interop", request.getServiceName());
        assertEquals("add", request.getTargetMethod());
        assertEquals(0, request.getRequestID());
        assertFalse(request.isMessage());
    }

    @Test
    public void inheritedGeneratedMethodIsAvailableThroughFacade() throws Exception {
        // Facade 继承子 IDL 服务，echo 仍来自父服务。
        ThriftMessage message = this.message("echo", TMessageType.CALL, new InteropService.echo_args("inherited"));
        RequestPayload request = this.codec.decodeRequest(message);

        // 推断资源保留父 Processor 的方法，而不要求 Facade 重复声明。
        assertEquals("echo", request.getTargetMethod());
        assertEquals(List.of("inherited"), request.getParameterValues());
    }

    @Test
    public void identicalInheritedSignaturesRemainOneCallableIdlMethod() throws Exception {
        // 两个普通父接口声明相同签名，通过显式资源绑定同一个 IDL 方法。
        ServiceDomain<SharedSignatureFacade> metadata = this.boundService(SharedSignatureFacade.class);
        assertNotNull(this.prepareBinding(this.protocol, metadata));
        this.context.publisher().rsfService(SharedSignatureFacade.class, value -> value) //
                .name("Shared").aliasName("thrift", "shared") //
                .bindResource("thrift", new ThriftServiceResource(InteropService.class)).register();
        ProtocolConfig protocol = ProtocolConfig.newConfig("thrift", "thrift", Map.of("service", "shared"));
        RouteMountIndex routes = new RouteMountIndex("tcp-0", protocol);
        ThriftCodec codec = new ThriftCodec(protocol, routes, this.context);
        ThriftMessage original = this.message("echo", TMessageType.CALL, new InteropService.echo_args("shared"));

        // 重复继承不属于 Java 重载，解码与再次编码都使用同一个参数签名。
        RequestPayload request = codec.decodeRequest(original);
        assertEquals("Shared", request.getServiceName());
        assertEquals(List.of("shared"), request.getParameterValues());
        ThriftMessage encoded = this.wire.decode(codec.encodeRequest(request, 41));
        InteropService.echo_args arguments = this.readBinaryBody(encoded, new InteropService.echo_args());
        assertEquals("shared", arguments.getValue());
        assertEquals(41, encoded.sequenceId());
    }

    @Test
    public void redundantGeneratedParentKeepsTheMostSpecificInheritedService() throws Exception {
        // 同时继承子 IDL 和其父 Iface，不绑定资源，由插件推断最具体的子服务。
        ServiceDomain<RedundantParentFacade> metadata = new ServiceDomain<>(RedundantParentFacade.class);
        assertNotNull(this.prepareBinding(this.protocol, metadata));
        this.context.publisher().rsfService(RedundantParentFacade.class, new RedundantParentFacadeImpl()) //
                .name("Redundant").aliasName("thrift", "redundant").register();
        ProtocolConfig protocol = ProtocolConfig.newConfig("thrift", "thrift", Map.of("service", "redundant"));
        RouteMountIndex routes = new RouteMountIndex("tcp-0", protocol);
        ThriftCodec codec = new ThriftCodec(protocol, routes, this.context);
        ExtendedInteropService.extended_args arguments = new ExtendedInteropService.extended_args("child");
        ThriftMessage original = this.message("extended", TMessageType.CALL, arguments);

        // 子服务独有的方法仍能解码和编码，证明未误选父资源或误报继承歧义。
        RequestPayload request = codec.decodeRequest(original);
        assertEquals("Redundant", request.getServiceName());
        assertEquals("extended", request.getTargetMethod());
        assertEquals(List.of("child"), request.getParameterValues());
        ThriftMessage encoded = this.wire.decode(codec.encodeRequest(request, 42));
        ExtendedInteropService.extended_args decoded = new ExtendedInteropService.extended_args();
        this.readBinaryBody(encoded, decoded);
        assertEquals("child", decoded.getValue());
        assertEquals(42, encoded.sequenceId());
    }

    @Test
    public void nativeBinaryRequestEncodingUsesOnlyTheRemainingBuffer() throws Exception {
        // 原生 SDK 接口声明 ByteBuffer，调用方只提交当前位置到 limit 之间的字节。
        ByteBuffer input = ByteBuffer.wrap(new byte[] { 9, 1, 2, 3, 8 });
        input.position(1);
        input.limit(4);
        RequestPayload request = this.request("echoBinary");
        request.addParameter(ByteBuffer.class.getName(), input);

        // 编码使用 SDK binary 字段，并保留调用方缓冲区的当前位置。
        ThriftMessage message = this.wire.decode(this.codec.encodeRequest(request, 12));
        InteropService.echoBinary_args arguments = this.readBinaryBody(message, new InteropService.echoBinary_args());
        assertArrayEquals(new byte[] { 1, 2, 3 }, arguments.getValue());
        assertEquals(1, input.position());
        assertEquals(4, input.limit());
    }

    @Test
    public void nativeBinaryRequestDecodingAdaptsSdkByteArrayAccessorToByteBuffer() throws Exception {
        // 生成的 args.getFieldValue 返回 byte[]，Iface 参数类型仍是 ByteBuffer。
        ByteBuffer value = ByteBuffer.wrap(new byte[] { 1, 2, 3 });
        InteropService.echoBinary_args arguments = new InteropService.echoBinary_args(value);
        ThriftMessage message = this.message("echoBinary", TMessageType.CALL, arguments);
        RequestPayload request = this.codec.decodeRequest(message);

        // 参数恢复为方法需要的原生缓冲区，不能把 byte[] 直接交给 Dispatcher。
        assertEquals(List.of(ByteBuffer.class.getName()), request.getParameterTypes());
        Object parameter = request.getParameterValues().get(0);
        assertTrue(parameter instanceof ByteBuffer);
        assertEquals(value, parameter);
    }

    @Test
    public void nativeBinaryResponseEncodingUsesOnlyTheRemainingBuffer() throws Exception {
        // 返回值沿用原生 ByteBuffer，并且可能只开放缓冲区的一部分。
        ByteBuffer value = ByteBuffer.wrap(new byte[] { 9, 4, 5, 6, 8 });
        value.position(1);
        value.limit(4);
        RequestPayload request = this.request("echoBinary");
        InteropService.echoBinary_args arguments = new InteropService.echoBinary_args(ByteBuffer.allocate(0));
        ThriftMessage original = this.message("echoBinary", TMessageType.CALL, arguments);
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);

        // SDK result 读取到剩余字节，并且编码不会消费业务返回值。
        ThriftMessage encoded = this.wire.decode(this.codec.encodeResponse(request, response, original));
        InteropService.echoBinary_result result = this.readBinaryBody(encoded, new InteropService.echoBinary_result());
        assertArrayEquals(new byte[] { 4, 5, 6 }, result.getSuccess());
        assertEquals(1, value.position());
        assertEquals(4, value.limit());
    }

    @Test
    public void nativeBinaryResponseDecodingAdaptsSdkByteArrayAccessorToByteBuffer() throws Throwable {
        // SDK result 的 binary 字段访问器与 Iface 返回类型使用不同 Java 表示。
        RequestPayload request = this.request("echoBinary");
        request.setRequestID(35);
        InteropService.echoBinary_result result = new InteropService.echoBinary_result();
        result.setSuccess(ByteBuffer.wrap(new byte[] { 4, 5, 6 }));
        ThriftMessage message = this.message("echoBinary", TMessageType.REPLY, result);

        // 返回值转换为 ByteBuffer，并保留 Meteor 本地请求关联。
        ResponsePayload response = this.codec.decodeResponse(request, message);
        assertEquals(ByteBuffer.class.getName(), response.getReturnType());
        assertEquals(ByteBuffer.wrap(new byte[] { 4, 5, 6 }), response.getReturnData());
        assertEquals(35, response.getRequestID());
    }

    @Test
    public void byteArrayFacadeIsCompatibleWithGeneratedBinarySignature() {
        // 普通 Facade 可用 byte[] 表达 IDL binary，但必须显式绑定生成资源。
        ServiceDomain<ByteArrayFacade> service = this.boundService(ByteArrayFacade.class);
        assertNotNull(this.prepareBinding(this.protocol, service));
    }

    @Test
    public void byteArrayFacadeRoundTripUsesExplicitGeneratedBinaryResource() throws Throwable {
        // 普通 byte[] 接口通过本地资源关联 IDL，端点选项排除其他已发布服务。
        this.context.publisher().rsfService(ByteArrayFacade.class, value -> value) //
                .name("Binary").aliasName("thrift", "binary") //
                .bindResource("thrift", new ThriftServiceResource(InteropService.class)).register();
        ProtocolConfig protocol = ProtocolConfig.newConfig("thrift", "thrift", Map.of("service", "binary"));
        RouteMountIndex routes = new RouteMountIndex("tcp-0", protocol);
        ThriftCodec codec = new ThriftCodec(protocol, routes, this.context);
        byte[] input = { 7, 8, 9 };
        InteropService.echoBinary_args arguments = new InteropService.echoBinary_args(ByteBuffer.wrap(input));
        ThriftMessage original = this.message("echoBinary", TMessageType.CALL, arguments);

        // 解码到普通接口后，参数与返回值都保持 byte[] 表示。
        RequestPayload request = codec.decodeRequest(original);
        assertEquals(List.of(byte[].class.getName()), request.getParameterTypes());
        assertArrayEquals(input, (byte[]) request.getParameterValues().get(0));
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(input);
        ThriftMessage encoded = this.wire.decode(codec.encodeResponse(request, response, original));
        ResponsePayload decoded = codec.decodeResponse(request, encoded);
        assertEquals(byte[].class.getName(), decoded.getReturnType());
        assertArrayEquals(input, (byte[]) decoded.getReturnData());
    }

    @Test
    public void declaredFailureIsEncodedAsReplyAndDecodedAsItsDeclaredType() throws Throwable {
        // 业务异常属于 IDL throws 声明，应进入 result 的异常字段。
        RequestPayload request = this.request("fail");
        ThriftMessage original = this.message("fail", TMessageType.CALL, new InteropService.fail_args("denied"));
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.InvokeError);
        response.setFailure(new InvocationTargetException(new DeclaredFailure(403, "denied")));

        // 声明异常仍使用 REPLY，客户端得到原始 IDL 异常及其结构化字段。
        ThriftMessage encoded = this.wire.decode(this.codec.encodeResponse(request, response, original));
        assertEquals(TMessageType.REPLY, encoded.messageType());
        DeclaredFailure failure = assertThrows(DeclaredFailure.class, //
                () -> this.codec.decodeResponse(request, encoded));
        assertEquals(403, failure.getCode());
        assertEquals("denied", failure.getReason());
    }

    @Test
    public void unexpectedFailureIsEncodedAsApplicationException() throws Exception {
        // 未出现在 IDL throws 中的异常不能放进生成 result。
        RequestPayload request = this.request("crash");
        ThriftMessage original = this.message("crash", TMessageType.CALL, new InteropService.crash_args("broken"));
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.InvokeError);
        response.setFailure(new IllegalStateException("broken"));

        // 客户端可见标准 EXCEPTION 消息和 INTERNAL_ERROR 错误码。
        ThriftMessage encoded = this.wire.decode(this.codec.encodeResponse(request, response, original));
        assertEquals(TMessageType.EXCEPTION, encoded.messageType());
        TApplicationException failure = assertThrows(TApplicationException.class, //
                () -> this.codec.decodeResponse(request, encoded));
        assertEquals(TApplicationException.INTERNAL_ERROR, failure.getType());
        assertEquals("broken", failure.getMessage());
    }

    @Test
    public void voidMethodStillProducesAReply() throws Throwable {
        // ping 是普通 void 方法，与 IDL oneway 的 notify 不同。
        RequestPayload request = this.request("ping");
        ThriftMessage original = this.message("ping", TMessageType.CALL, new InteropService.ping_args());
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);

        // 空 result 仍发送标准 REPLY，客户端以 null 结束本次调用。
        ThriftMessage encoded = this.wire.decode(this.codec.encodeResponse(request, response, original));
        assertEquals(TMessageType.REPLY, encoded.messageType());
        assertEquals(21, encoded.sequenceId());
        this.readBinaryBody(encoded, new InteropService.ping_result());
        ResponsePayload decoded = this.codec.decodeResponse(request, encoded);
        assertEquals(ProtocolStatus.OK, decoded.getStatus());
        assertEquals("void", decoded.getReturnType());
        assertNull(decoded.getReturnData());
    }

    @Test
    public void nonVoidResponseWithoutSuccessIsRejected() throws Exception {
        // REPLY 报文语法合法，但没有返回值也没有声明异常。
        RequestPayload request = this.request("echo");
        ThriftMessage message = this.message("echo", TMessageType.REPLY, new InteropService.echo_result());

        // 客户端得到 MISSING_RESULT，不能把缺失结果作为 null 成功返回。
        TApplicationException failure = assertThrows(TApplicationException.class, //
                () -> this.codec.decodeResponse(request, message));
        assertEquals(TApplicationException.MISSING_RESULT, failure.getType());
    }

    @Test
    public void wrongResponseMethodIsRejected() throws Exception {
        // 当前请求是 echo，而响应头声称属于 crash。
        RequestPayload request = this.request("echo");
        ThriftMessage message = this.message("crash", TMessageType.REPLY, new InteropService.crash_result());

        // 方法名不匹配时，不能用另一个生成 result 解析本次响应。
        TApplicationException failure = assertThrows(TApplicationException.class, //
                () -> this.codec.decodeResponse(request, message));
        assertEquals(TApplicationException.WRONG_METHOD_NAME, failure.getType());
    }

    @Test
    public void requestArgumentCountMustMatchTheIdl() {
        // add 要求两个参数，本次只提供 left。
        RequestPayload request = this.request("add");
        request.addParameter("int", 7);

        // 参数数量错误在编码前失败，不能写出缺省 right 的请求。
        TApplicationException failure = assertThrows(TApplicationException.class, //
                () -> this.codec.encodeRequest(request, 1));
        assertEquals(TApplicationException.PROTOCOL_ERROR, failure.getType());
    }

    @Test
    public void requestSerializerCannotReplaceGeneratedThriftSerialization() {
        // 单次请求不能通过 serializeType 把 IDL 参数改用通用 JSON 序列化。
        RequestPayload request = this.request("echo");
        request.addParameter(String.class.getName(), "value");
        request.setSerializeType("json");

        // 编码前拒绝错误配置，并提示通过 encoding 选择 Thrift 线协议编码。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> this.codec.encodeRequest(request, 1));
        assertTrue(failure.getMessage(), failure.getMessage().contains("encoding"));
    }

    @Test
    public void onewayRequestIsEncodedWithOnewayMessageType() throws Exception {
        // Java @MetMessage 与 IDL oneway 同时声明消息调用。
        RequestPayload request = this.request("notify");
        request.setMessage(true);
        request.addParameter(String.class.getName(), "notice");

        // 消息类型保留 SDK 的 ONEWAY 语义。
        ThriftMessage message = this.wire.decode(this.codec.encodeRequest(request, 14));
        assertEquals(TMessageType.ONEWAY, message.messageType());
        assertEquals("notify", message.methodName());
    }

    @Test
    public void onewayRequestIsDecodedAsMeteorMessage() throws Exception {
        // 对端通过标准 ONEWAY 头调用 IDL notify。
        ThriftMessage message = this.message("notify", TMessageType.ONEWAY, new InteropService.notify_args("notice"));
        RequestPayload request = this.codec.decodeRequest(message);

        // Dispatcher 收到消息标记，不等待业务最终响应。
        assertTrue(request.isMessage());
        assertEquals(List.of("notice"), request.getParameterValues());
    }

    @Test
    public void callHeaderCannotInvokeAnIdlOneway() throws Exception {
        // 即使参数正确，CALL 头也不能调用 IDL oneway 方法。
        ThriftMessage message = this.message("notify", TMessageType.CALL, new InteropService.notify_args("notice"));

        // 服务端拒绝不一致的消息类型。
        TApplicationException failure = assertThrows(TApplicationException.class, //
                () -> this.codec.decodeRequest(message));
        assertEquals(TApplicationException.INVALID_MESSAGE_TYPE, failure.getType());
    }

    @Test
    public void meteorMessageCannotEncodeAnOrdinaryIdlCall() {
        // ping 在 IDL 中不是 oneway，调用方不能擅自把它作为 Meteor 消息发送。
        RequestPayload request = this.request("ping");
        request.setMessage(true);

        // 编码端拒绝调用方式不一致。
        TApplicationException failure = assertThrows(TApplicationException.class, //
                () -> this.codec.encodeRequest(request, 1));
        assertEquals(TApplicationException.PROTOCOL_ERROR, failure.getType());
    }

    @Test
    public void onewayCannotProduceABusinessResponse() throws Exception {
        // IDL oneway 没有可供编码的 result 类型。
        RequestPayload request = this.request("notify");
        ThriftMessage original = this.message("notify", TMessageType.ONEWAY, new InteropService.notify_args("notice"));
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);

        // 编解码层也明确拒绝为 oneway 生成业务响应。
        TApplicationException failure = assertThrows(TApplicationException.class, //
                () -> this.codec.encodeResponse(request, response, original));
        assertEquals(TApplicationException.INVALID_MESSAGE_TYPE, failure.getType());
    }

    @Test
    public void multiplexedCallRepliesWithBareMethodName() throws Throwable {
        // Multiplexing 只给请求添加服务名前缀，标准响应头使用裸方法名。
        ProtocolConfig protocol = ProtocolConfig.newConfig("thrift", "thrift", Map.of("multiplexed", "true"));
        RouteMountIndex routes = new RouteMountIndex("tcp-0", protocol);
        ThriftCodec codec = new ThriftCodec(protocol, routes, this.context);
        ThriftMessage original = this.message("interop:echo", TMessageType.CALL, new InteropService.echo_args("mux"));
        RequestPayload request = codec.decodeRequest(original);
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData("mux");

        // 响应保留序号，并能由同一请求上下文按裸方法名解码。
        ThriftMessage encoded = this.wire.decode(codec.encodeResponse(request, response, original));
        assertEquals("echo", encoded.methodName());
        assertEquals(21, encoded.sequenceId());
        assertEquals("mux", codec.decodeResponse(request, encoded).getReturnData());
    }

    @Test
    public void multiplexedErrorsAlsoUseBareMethodName() throws Exception {
        // 请求已经带有服务前缀，错误响应同样遵守标准裸方法名规则。
        ThriftMessage original = this.message("interop:echo", TMessageType.CALL, new InteropService.echo_args("mux"));
        TApplicationException failure = new TApplicationException(TApplicationException.UNKNOWN_METHOD, "missing");
        byte[] bytes = this.codec.encodeError(original, failure);

        // 错误响应不重复服务前缀，并保留输入消息的关联序号。
        ThriftMessage encoded = this.wire.decode(bytes);
        assertEquals("echo", encoded.methodName());
        assertEquals(TMessageType.EXCEPTION, encoded.messageType());
        assertEquals(21, encoded.sequenceId());
    }

    @Test
    public void multiplexedEndpointRequiresAServicePrefix() throws Exception {
        // 显式开启 multiplexing 后，裸方法名不再能够确定目标服务。
        ProtocolConfig protocol = ProtocolConfig.newConfig("thrift", "thrift", Map.of("multiplexed", "true"));
        RouteMountIndex routes = new RouteMountIndex("tcp-0", protocol);
        ThriftCodec codec = new ThriftCodec(protocol, routes, this.context);
        ThriftMessage message = this.message("echo", TMessageType.CALL, new InteropService.echo_args("value"));

        // 缺失服务前缀作为 UNKNOWN_METHOD 返回。
        TApplicationException failure = assertThrows(TApplicationException.class, () -> codec.decodeRequest(message));
        assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
    }

    @Test
    public void nonMultiplexedEndpointRejectsAmbiguousServices() throws Exception {
        // 同一非复用端点挂载两个服务，裸方法头不能选择其中一个。
        this.context.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                .name("Other").aliasName("thrift", "other").register();
        ThriftMessage message = this.message("echo", TMessageType.CALL, new InteropService.echo_args("value"));

        // 在参数解析前拒绝歧义，不能依赖服务遍历顺序。
        TApplicationException failure = assertThrows(TApplicationException.class, //
                () -> this.codec.decodeRequest(message));
        assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
        assertTrue(failure.getMessage().contains("Ambiguous"));
    }

    @Test
    public void selectedServiceMakesANonMultiplexedEndpointUnambiguous() throws Exception {
        // service 选项明确绑定其中一个服务，外部请求仍只携带方法名。
        this.context.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                .name("Other").aliasName("thrift", "other").register();
        ProtocolConfig protocol = ProtocolConfig.newConfig("thrift", "thrift", Map.of("service", "other"));
        RouteMountIndex routes = new RouteMountIndex("tcp-0", protocol);
        ThriftCodec codec = new ThriftCodec(protocol, routes, this.context);
        ThriftMessage message = this.message("echo", TMessageType.CALL, new InteropService.echo_args("value"));

        // 本次调用只解析到显式选择的服务。
        RequestPayload request = codec.decodeRequest(message);
        assertEquals("Other", request.getServiceName());
        assertEquals(List.of("value"), request.getParameterValues());
    }

    @Test
    public void nativeServiceAliasCannotExposeAServiceFromAnotherEndpoint() throws Exception {
        // 服务实际发布到 tcp-0，另一端点即使使用相同协议及别名也不能解析到它。
        ProtocolConfig protocol = ProtocolConfig.newConfig("thrift", null, Map.of());
        RouteMountIndex routes = new RouteMountIndex("other-endpoint", protocol);
        ThriftCodec codec = new ThriftCodec(protocol, routes, this.context);
        ThriftMessage message = this.message("echo", TMessageType.CALL, new InteropService.echo_args("hidden"));

        // 不读取未被当前端点接受的服务契约，按原生未知方法语义返回失败。
        TApplicationException failure = assertThrows(TApplicationException.class, () -> codec.decodeRequest(message));
        assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
    }

    @Test
    public void defaultHttpContextDoesNotExposeAnExplicitPrivateService() throws Exception {
        // HTTP 声明两个挂载，服务只选择附加的 private 上下文。
        MetConfiguration configuration = this.httpConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .name("Private").contextPathByPrefix("thrift-http", "/private").register();
            ProtocolConfig protocol = this.httpProtocol();
            RouteMountIndex routes = new RouteMountIndex("http-0", protocol);
            ThriftCodec codec = new ThriftCodec(protocol, routes, context);
            ThriftMessage message = this.message("echo", TMessageType.CALL, new InteropService.echo_args("hidden"));
            RouteMount mount = routes.match("/rpc");

            // 默认挂载不能看到被显式排除的服务。
            TApplicationException failure = assertThrows(TApplicationException.class, //
                    () -> codec.decodeRequest(message, mount));
            assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
        }
    }

    @Test
    public void selectedHttpContextExposesItsPrivateService() throws Exception {
        // HTTP 服务显式选择 private 挂载，默认上下文不参与选择。
        MetConfiguration configuration = this.httpConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .name("Private").contextPathByPrefix("thrift-http", "/private").register();
            ProtocolConfig protocol = this.httpProtocol();
            RouteMountIndex routes = new RouteMountIndex("http-0", protocol);
            ThriftCodec codec = new ThriftCodec(protocol, routes, context);
            ThriftMessage message = this.message("echo", TMessageType.CALL, new InteropService.echo_args("visible"));

            // 选中的挂载可解码该服务，客户端请求路径也采用同一选择。
            RequestPayload request = codec.decodeRequest(message, routes.match("/private"));
            assertEquals("Private", request.getServiceName());
            assertEquals("/private", codec.requestPath(request));
        }
    }

    @Test
    public void undeclaredAdditionalHttpContextCannotSeeADefaultService() throws Exception {
        // 未指定服务上下文时，只使用协议的默认挂载。
        MetConfiguration configuration = this.httpConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .name("Default").register();
            ProtocolConfig protocol = this.httpProtocol();
            RouteMountIndex routes = new RouteMountIndex("http-0", protocol);
            ThriftCodec codec = new ThriftCodec(protocol, routes, context);
            ThriftMessage message = this.message("echo", TMessageType.CALL, new InteropService.echo_args("hidden"));
            RouteMount mount = routes.match("/private");

            // 仅在端点上声明附加挂载，并不会把默认服务同时暴露过去。
            TApplicationException failure = assertThrows(TApplicationException.class, //
                    () -> codec.decodeRequest(message, mount));
            assertEquals(TApplicationException.UNKNOWN_METHOD, failure.getType());
        }
    }

    @Test
    public void facadeWithoutGeneratedResourceIsRejected() {
        // 普通接口既不继承生成 Iface，也没有绑定本地 IDL 资源。
        ServiceDomain<ByteArrayFacade> service = new ServiceDomain<>(ByteArrayFacade.class);
        assertNull(this.prepareBinding(this.protocol, service));
    }

    @Test
    public void publicationSerializerRejectsOnlyTheThriftProtocol() {
        // JSON 不能替代原生 IDL 编码，但服务本身仍允许登记并保留本地调用。
        MetBindInfo<ThriftDemoService> service = this.context.publisher() //
                .rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                .name("JsonSerializer").serialize("json").register();

        // 拒绝结果不会被声明列表误认为已经发布到 Thrift 端点。
        ServiceDomain<?> registered = MetBindInfoWrap.unwrap(service);
        assertNull(registered.getProtocolMeta("thrift"));
        assertFalse(registered.getProtocolMetas().containsKey("thrift"));
    }

    @Test
    public void idlOnewayRequiresMeteorMessageDeclaration() {
        // 普通 notify 接口绑定了 IDL，但没有声明 Meteor 消息语义。
        ServiceDomain<UnmarkedOneway> service = this.boundService(UnmarkedOneway.class);
        assertThrows(IllegalArgumentException.class, () -> this.prepareBinding(this.protocol, service));
    }

    @Test
    public void meteorMessageDeclarationRequiresIdlOneway() {
        // ping 在 IDL 中是普通 void 调用，不能仅添加 @MetMessage 改变语义。
        ServiceDomain<MarkedOrdinaryCall> service = this.boundService(MarkedOrdinaryCall.class);
        assertThrows(IllegalArgumentException.class, () -> this.prepareBinding(this.protocol, service));
    }

    @Test
    public void javaOverloadsCannotShareOneIdlMethod() {
        // IDL 一个方法名只有一份 args/result，不能承载 Java 重载。
        ServiceDomain<OverloadedFacade> service = this.boundService(OverloadedFacade.class);
        assertThrows(IllegalArgumentException.class, () -> this.prepareBinding(this.protocol, service));
    }

    @Test
    public void facadeReturnTypeMustMatchGeneratedIdl() {
        // echo 的 IDL 返回 string，普通接口不能改为整数。
        ServiceDomain<WrongReturnFacade> service = this.boundService(WrongReturnFacade.class);
        assertThrows(IllegalArgumentException.class, () -> this.prepareBinding(this.protocol, service));
    }

    @Test
    public void streamingFacadeCannotBeBoundToThrift() {
        // 原生 Thrift 请求响应模型不能承载持续输出的 Publisher。
        ServiceDomain<StreamingFacade> service = this.boundService(StreamingFacade.class);
        assertThrows(IllegalArgumentException.class, () -> this.prepareBinding(this.protocol, service));
    }

    private Object prepareBinding(ProtocolConfig protocol, ServiceDomain<?> service) {
        ProtocolMeta meta = service.getProtocolMeta(protocol.name());
        if (meta == null) {
            meta = new ProtocolMeta(service, null, null, Map.of());
        }
        return new ThriftProtocol().prepareBinding(protocol, null, meta);
    }

    private RequestPayload request(String method) {
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(this.service.getBindGroup());
        request.setServiceName(this.service.getBindName());
        request.setServiceVersion(this.service.getBindVersion());
        request.setTargetMethod(method);
        return request;
    }

    private ThriftMessage message(String name, byte type, TBase<?, ?> body) throws TException {
        return this.wire.decode(this.wire.encode("binary", name, type, 21, body));
    }

    private <T extends TBase<?, ?>> T readBinaryBody(ThriftMessage message, T body) throws TException {
        TProtocol input = new TBinaryProtocol(new TMemoryInputTransport(message.data()));
        input.readMessageBegin();
        body.read(input);
        input.readMessageEnd();
        return body;
    }

    private <T> ServiceDomain<T> boundService(Class<T> type) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindResource("thrift", new ThriftServiceResource(InteropService.class));
        return service;
    }

    private MetConfiguration httpConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.connector("http").protocol("thrift-http").contextPath("/rpc").addContextPath("/private");
        return configuration;
    }

    private ProtocolConfig httpProtocol() {
        Map<String, String> options = Map.of("contextPath", "/rpc", "contextPaths", "[\"/private\"]");
        return ProtocolConfig.newConfig("thrift-http", "thrift-http", options);
    }

    public interface ByteArrayFacade {
        byte[] echoBinary(byte[] value);
    }

    public interface Left {
        String echo(String value);
    }

    public interface Right {
        String echo(String value);
    }

    public interface SharedSignatureFacade extends Left, Right {
    }

    public interface RedundantParentFacade extends ExtendedInteropService.Iface, InteropService.Iface {
        @Override
        @MetMessage
        void notify(String value) throws TException;
    }

    public static class RedundantParentFacadeImpl extends ThriftDemoServiceImpl implements RedundantParentFacade {
    }

    public interface UnmarkedOneway {
        void notify(String value);
    }

    public interface MarkedOrdinaryCall {
        @MetMessage
        void ping();
    }

    public interface OverloadedFacade {
        String echo(String value);

        String echo(int value);
    }

    public interface WrongReturnFacade {
        int echo(String value);
    }

    public interface StreamingFacade {
        Flow.Publisher<String> echo(String value);
    }
}
