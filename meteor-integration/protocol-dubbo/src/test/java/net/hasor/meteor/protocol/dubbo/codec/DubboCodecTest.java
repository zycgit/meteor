/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import net.hasor.example.protocol.dubbo.interop.DubboInteropPerson;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 直接验证经典 Dubbo 正文与 Meteor 业务契约之间的映射。 */
public class DubboCodecTest {
    @Test
    public void requestHasStandardVersionAndJvmDescriptor() throws Exception {
        // 用正式发布接口准备多参服务，编码器只负责线协议转换。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "combine", String.class, "hello", int.class, 3);
            DubboFrame frame = decode(codec(context).encode(request));

            // Dubbo 的参数描述没有括号，引用类型使用 JVM 内部名称。
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(frame.body()));
            assertEquals("2.0.2", input.readString());
            assertEquals("example.Dubbo", input.readString());
            assertEquals("1.0.0", input.readString());
            assertEquals("combine", input.readString());
            assertEquals("Ljava/lang/String;I", input.readString());
            assertTrue(frame.request());
            assertTrue(frame.twoWay());
            assertEquals(2, frame.serializationId());
        }
    }

    @Test
    public void decodedRequestUsesPublishedJavaSignature() throws Exception {
        // 参数身份来自已发布接口，正文中的描述不参与任意类加载。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "combine", String.class, "hello", int.class, 3);
            RequestPayload result = codec(context).decodeRequest(decode(codec(context).encode(request)));
            assertEquals(List.of(String.class.getName(), "int"), result.getParameterTypes());
            assertEquals(List.of("hello", 3), result.getParameterValues());
            assertEquals(service.getBindName(), result.getServiceName());
            assertEquals(123, result.getRequestID());
        }
    }

    @Test
    public void primitiveOverloadMatchesPublishedDescriptor() throws Exception {
        // 同名方法由精确参数描述区分，而非参数值猜测。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "overloaded", int.class, 9);
            RequestPayload result = codec(context).decodeRequest(decode(codec(context).encode(request)));
            assertEquals(List.of("int"), result.getParameterTypes());
            assertEquals(List.of(9), result.getParameterValues());
        }
    }

    @Test
    public void arrayArgumentUsesJvmArrayDescriptor() throws Exception {
        // 字节数组保持二进制对象，不拆成多个 RPC 参数。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "binary", byte[].class, new byte[] { 1, 2, 3 });
            RequestPayload result = codec(context).decodeRequest(decode(codec(context).encode(request)));
            assertArrayEquals(new byte[] { 1, 2, 3 }, (byte[]) result.getParameterValues().get(0));
        }
    }

    @Test
    public void requestWithoutArgumentsPreservesEmptySignature() throws Exception {
        // 无参方法仍有五个固定字段和附加项 Map。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "noArgs");
            RequestPayload result = codec(context).decodeRequest(decode(codec(context).encode(request)));
            assertTrue(result.getParameterValues().isEmpty());
            assertEquals("noArgs", result.getTargetMethod());
        }
    }

    @Test
    public void nullReferenceArgumentIsPreserved() throws Exception {
        // null 是业务实参，不当作未提供参数。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "nullable", String.class, null);
            RequestPayload result = codec(context).decodeRequest(decode(codec(context).encode(request)));
            assertEquals(1, result.getParameterValues().size());
            assertNull(result.getParameterValues().get(0));
        }
    }

    @Test
    public void messageRequestClearsTwoWayFlag() throws Exception {
        // 消息模式只提交请求，不要求远端业务最终响应。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "notice", String.class, "notice");
            request.setMessage(true);
            DubboFrame frame = decode(codec(context).encode(request));
            assertTrue(frame.request());
            assertFalse(frame.twoWay());
            assertTrue(codec(context).decodeRequest(frame).isMessage());
        }
    }

    @Test
    public void requestMetadataAndTimeoutAreAttachments() throws Exception {
        // 普通用户选项无须协议专属前缀。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "echo", String.class, "value");
            request.addOption("x-trace", "trace-1");
            request.addOption("timeout", "99999");
            request.setClientTimeout(1500);

            // 调用剩余期限覆盖用户伪造的 timeout 附加项。
            RequestPayload result = codec(context).decodeRequest(decode(codec(context).encode(request)));
            assertEquals("trace-1", result.getOption("x-trace"));
            assertEquals(1500, result.getClientTimeout());
        }
    }

    @Test
    public void zeroRequestTimeoutLeavesTheServerDefaultUnspecified() throws Exception {
        // 旧 Dubbo-Go 将有效客户端期限换算成 0；入站按未声明远端期限交给统一默认值处理。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.setClientTimeout(0);
            request.addOption("timeout", "0");

            // 编解码不创建 1ms 期限，也不把 0 改成无限期限的特殊值。
            RequestPayload decoded = codec(context).decodeRequest(decode(codec(context).encode(request)));
            assertEquals(0, decoded.getClientTimeout());
            assertEquals("0", decoded.getOption("timeout"));
        }
    }

    @Test
    public void negativeRequestTimeoutIsRejected() throws Exception {
        // 兼容 0 不放宽负数期限。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.setClientTimeout(0);
            request.addOption("timeout", "-1");
            DubboFrame frame = decode(codec(context).encode(request));
            assertThrows(IOException.class, () -> codec(context).decodeRequest(frame));
        }
    }

    @Test
    public void nonNumericRequestTimeoutIsRejected() throws Exception {
        // 非法单位不能靠字符串猜测转换成有效毫秒期限。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.setClientTimeout(0);
            request.addOption("timeout", "10s");
            DubboFrame frame = decode(codec(context).encode(request));
            assertThrows(IOException.class, () -> codec(context).decodeRequest(frame));
        }
    }

    @Test
    public void overflowingRequestTimeoutIsRejected() throws Exception {
        // 宽限 0 不让整型溢出绕回默认期限。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.setClientTimeout(0);
            request.addOption("timeout", "2147483648");
            DubboFrame frame = decode(codec(context).encode(request));
            assertThrows(IOException.class, () -> codec(context).decodeRequest(frame));
        }
    }

    @Test
    public void explicitWireIdentityOptionsOverrideRegistryMetadata() throws Exception {
        // 单次调用身份优先于注册中心映射，不修改本地服务绑定。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "noArgs");
            service.setMetaData("dubbo.registry.group", "");
            service.setMetaData("dubbo.registry.version", "");
            request.addOption("dubbo.group", "default");
            request.addOption("dubbo.version", "1.0.0");
            DubboFrame wire = decode(codec(context).encode(request));
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(wire.body()));
            input.readString();
            input.readString();
            assertEquals("1.0.0", input.readString());
            input.readString();
            input.readString();
            Map<?, ?> attachments = (Map<?, ?>) input.readObject(Map.class);
            assertEquals("default", attachments.get("group"));
            assertEquals("1.0.0", attachments.get("version"));
            assertFalse(attachments.containsKey("dubbo.group"));
            assertFalse(attachments.containsKey("dubbo.version"));
        }
    }

    @Test
    public void explicitGroupSelectsMatchingProviderAfterReadingArguments() throws Exception {
        // group 位于附加项中，不能在参数解码前误判同名服务歧义。
        try (MetContext context = configuration().buildContext()) {
            context.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .group("blue").aliasName("dubbo", "example.Dubbo").serialize("Hessian2").register();
            MetBindInfo<?> green = context.publisher() //
                    .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .group("green").aliasName("dubbo", "example.Dubbo").serialize("Hessian2").register();
            RequestPayload request = request(green, "echo", String.class, "green");

            // 参数恢复后由附加项选择 green，不能派发到 blue。
            RequestPayload decoded = codec(context).decodeRequest(decode(codec(context).encode(request)));
            assertEquals("green", decoded.getServiceGroup());
        }
    }

    @Test
    public void absentGroupCannotAddressAnyGroupedProvider() throws Exception {
        // 缺少 group 是未分组身份，不能作为任意分组的通配符。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> first = context.publisher() //
                    .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .group("blue").aliasName("dubbo", "example.Dubbo").serialize("Hessian2").register();
            context.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .group("green").aliasName("dubbo", "example.Dubbo").serialize("Hessian2").register();
            RequestPayload request = request(first, "echo", String.class, "value");
            request.addOption("dubbo.group", "");
            DubboFrame frame = decode(codec(context).encode(request));
            assertThrows(IOException.class, () -> codec(context).decodeRequest(frame));
        }
    }

    @Test
    public void unknownMethodCannotBeEncoded() throws Exception {
        // 出站方法同样必须来自本地正式声明。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "missing");
            assertThrows(IOException.class, () -> codec(context).encode(request));
        }
    }

    @Test
    public void requestArgumentCountMustMatchMethod() throws Exception {
        // 类型声明存在但值缺失时不发送不完整正文。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.getParameterValues().clear();
            assertThrows(IOException.class, () -> codec(context).encode(request));
        }
    }

    @Test
    public void responseValueAndMetadataRoundTrip() throws Exception {
        // 正常返回使用 flag4，附加项与业务值分别解码。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            ResponsePayload response = success("answer");
            response.addOption("x-reply", "response-1");
            byte[] encoded = codec(context).encode(request, response);
            DubboFrame frame = decode(encoded);
            ResponsePayload decoded = codec(context).decodeResponse(request, frame);
            assertEquals(ProtocolStatus.OK, decoded.getStatus());
            assertEquals("answer", decoded.getReturnData());
            assertEquals("response-1", decoded.getOption("x-reply"));
            assertEquals(123, decoded.getRequestID());
        }
    }

    @Test
    public void legacyClientReceivesValueWithoutResponseAttachments() throws Exception {
        // 旧发行版声明不支持附加项，即使业务设置了 metadata，也只能返回 flag1 和业务值。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.addOption("dubbo", "2.5.3");
            ResponsePayload response = success("answer");
            response.addOption("trace", "legacy-cannot-read-map");

            // 完整正文在业务值之后结束，不遗留旧客户端无法读取的 Map。
            DubboFrame frame = decode(codec(context).encode(request, response));
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(frame.body()));
            assertEquals(1, input.readInt());
            assertEquals("answer", input.readString());
            assertEquals(-1, input.read());
        }
    }

    @Test
    public void legacyClientReceivesNullWithoutResponseAttachments() throws Exception {
        // 旧客户端的 null 成功结果只有 flag2。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "nullable", String.class, null);
            request.addOption("dubbo", "2.5.3");
            DubboFrame frame = decode(codec(context).encode(request, success(null)));
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(frame.body()));
            assertEquals(2, input.readInt());
            assertEquals(-1, input.read());
        }
    }

    @Test
    public void legacyClientReceivesExceptionWithoutResponseAttachments() throws Exception {
        // 不支持 metadata 不影响真实业务异常按 flag0 返回。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "fail", String.class, "broken");
            request.addOption("dubbo", "2.5.3");
            ResponsePayload response = new ResponsePayload();
            response.setStatus(ProtocolStatus.InvokeError);
            response.setFailure(new IllegalArgumentException("legacy-error"));
            DubboFrame frame = decode(codec(context).encode(request, response));
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(frame.body()));
            assertEquals(0, input.readInt());
            assertEquals("legacy-error", ((Throwable) input.readObject()).getMessage());
            assertEquals(-1, input.read());
        }
    }

    @Test
    public void highestOfficialProtocolVersionPreservesResponseAttachments() throws Exception {
        // 官方区间上界仍使用新结果分支，不能只认识当前默认 2.0.2。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.addOption("dubbo", "2.0.99");
            ResponsePayload response = success("answer");
            response.addOption("trace", "retained");
            DubboFrame frame = decode(codec(context).encode(request, response));
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(frame.body()));
            assertEquals(4, input.readInt());
            assertEquals("answer", input.readString());
            assertEquals("retained", ((Map<?, ?>) input.readObject(Map.class)).get("trace"));
        }
    }

    @Test
    public void protocolVersionBelowTheAttachmentBoundaryUsesLegacyResults() throws Exception {
        // 2.0.1 不声明响应附加项，不能按字符串前缀误判成新协议。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.addOption("dubbo", "2.0.1");
            DubboFrame frame = decode(codec(context).encode(request, success("answer")));
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(frame.body()));
            assertEquals(1, input.readInt());
        }
    }

    @Test
    public void protocolVersionAboveTheAttachmentBoundaryUsesLegacyResults() throws Exception {
        // 2.0.100 超出官方协商区间，不能因数字更大就开启新结果分支。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.addOption("dubbo", "2.0.100");
            DubboFrame frame = decode(codec(context).encode(request, success("answer")));
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(frame.body()));
            assertEquals(1, input.readInt());
        }
    }

    @Test
    public void protocolVersionSuffixPreservesAdvertisedAttachmentSupport() throws Exception {
        // 与 Apache Dubbo 一样读取数字前缀，保留带发行后缀的协议版本。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.addOption("dubbo", "2.0.2-stable");
            ResponsePayload response = success("answer");
            response.addOption("trace", "retained");
            DubboFrame frame = decode(codec(context).encode(request, response));
            assertEquals("retained", codec(context).decodeResponse(request, frame).getOption("trace"));
        }
    }

    @Test
    public void missingProtocolVersionCannotInventAttachmentSupport() throws Exception {
        // 未提供版本上下文时保守生成旧结果，不能凭本地默认值臆测对端能力。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.removeOption("dubbo");
            DubboFrame frame = decode(codec(context).encode(request, success("answer")));
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(frame.body()));
            assertEquals(1, input.readInt());
        }
    }

    @Test
    public void invalidProtocolVersionCannotInventAttachmentSupport() throws Exception {
        // 非法版本只能退回无附加项结果，不能抛出未捕获的版本解析异常。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.addOption("dubbo", "not-a-version");
            DubboFrame frame = decode(codec(context).encode(request, success("answer")));
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(frame.body()));
            assertEquals(1, input.readInt());
        }
    }

    @Test
    public void overflowingProtocolVersionCannotInventAttachmentSupport() throws Exception {
        // 超大组件不能整型回绕后进入 2.0.2..2.0.99 区间。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            request.addOption("dubbo", "2.0.2147483648");
            DubboFrame frame = decode(codec(context).encode(request, success("answer")));
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(frame.body()));
            assertEquals(1, input.readInt());
        }
    }

    @Test
    public void attachmentCannotUpgradeLegacyRequestProtocolVersion() throws Exception {
        // 请求正文首字段是旧版本，附件中的新版本不能伪造附加项能力。
        try (MetContext context = configuration().buildContext()) {
            publish(context);
            RequestPayload request = codec(context).decodeRequest(versionedRequest("2.5.3", "2.0.2"));
            assertEquals("2.5.3", request.getOption("dubbo"));
            DubboFrame frame = decode(codec(context).encode(request, success("answer")));
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(frame.body()));
            assertEquals(1, input.readInt());
        }
    }

    @Test
    public void attachmentCannotDowngradeModernRequestProtocolVersion() throws Exception {
        // 请求正文首字段已声明新协议，附件中的旧版本不能丢掉响应 metadata。
        try (MetContext context = configuration().buildContext()) {
            publish(context);
            RequestPayload request = codec(context).decodeRequest(versionedRequest("2.0.2", "2.5.3"));
            ResponsePayload response = success("answer");
            response.addOption("trace", "retained");
            DubboFrame frame = decode(codec(context).encode(request, response));
            assertEquals("2.0.2", request.getOption("dubbo"));
            assertEquals("retained", codec(context).decodeResponse(request, frame).getOption("trace"));
        }
    }

    @Test
    public void nullReferenceResponseRoundTrips() throws Exception {
        // null 结果使用 flag5，不需要伪造对象或字符串。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "nullable", String.class, null);
            byte[] encoded = codec(context).encode(request, success(null));
            assertNull(codec(context).decodeResponse(request, decode(encoded)).getReturnData());
        }
    }

    @Test
    public void voidResponseRoundTrips() throws Exception {
        // void 仍是双向 RPC 成功结果，与消息模式分开。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "nothing");
            byte[] encoded = codec(context).encode(request, success(null));
            ResponsePayload result = codec(context).decodeResponse(request, decode(encoded));
            assertEquals(ProtocolStatus.OK, result.getStatus());
            assertEquals("void", result.getReturnType());
        }
    }

    @Test
    public void businessExceptionRetainsThrowable() throws Exception {
        // 正常响应状态内的异常标志保留业务异常对象。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "fail", String.class, "broken");
            ResponsePayload response = new ResponsePayload();
            response.setStatus(ProtocolStatus.InvokeError);
            response.setFailure(new IllegalArgumentException("broken"));
            byte[] encoded = codec(context).encode(request, response);

            // 不将业务错误误写成非 OK 协议状态的字符串。
            ResponsePayload result = codec(context).decodeResponse(request, decode(encoded));
            assertTrue(result.getFailure() instanceof IllegalArgumentException);
            assertEquals("broken", result.getFailure().getMessage());
        }
    }

    @Test
    public void serviceStatusBecomesDubboErrorStatus() throws Exception {
        // 服务未找到属于协议错误，正文只保存错误说明。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            ResponsePayload response = new ResponsePayload();
            response.setStatus(ProtocolStatus.NotFound);
            response.addOption("message", "service missing");
            DubboFrame wire = decode(codec(context).encode(request, response));
            assertEquals(60, wire.status());
            ResponsePayload result = codec(context).decodeResponse(request, wire);
            assertEquals(ProtocolStatus.NotFound, result.getStatus());
            assertEquals("service missing", result.getOption("message"));
        }
    }

    @Test
    public void legacyValueResponseWithoutAttachmentsIsAccepted() throws Exception {
        // 老 Dubbo 的 flag1 不携带附加项 Map。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame frame = resultFrame(1, "legacy", null);
            ResponsePayload result = codec(context).decodeResponse(request, frame);
            assertEquals("legacy", result.getReturnData());
        }
    }

    @Test
    public void legacyNullResponseWithoutAttachmentsIsAccepted() throws Exception {
        // flag2 正文只含结果标志。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "nullable", String.class, null);
            assertNull(codec(context).decodeResponse(request, resultFrame(2, null, null)).getReturnData());
        }
    }

    @Test
    public void legacyExceptionResponseWithoutAttachmentsIsAccepted() throws Exception {
        // flag0 保持异常对象，后面不强制读取附加项。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "fail", String.class, "broken");
            DubboFrame frame = resultFrame(0, new IllegalStateException("legacy"), null);
            ResponsePayload result = codec(context).decodeResponse(request, frame);
            assertEquals("legacy", result.getFailure().getMessage());
        }
    }

    @Test
    public void dubboGoLegacyValueAcceptsOneNullTerminator() throws Exception {
        // Dubbo-Go 旧结果分支在完整业务值之后额外写一个 NULL。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame frame = append(resultFrame(1, "中文🙂", null), (byte) 'N');
            ResponsePayload response = codec(context).decodeResponse(request, frame);
            assertEquals("中文🙂", response.getReturnData());
        }
    }

    @Test
    public void dubboGoValueWithAttachmentsAcceptsOneNullTerminator() throws Exception {
        // 终止标记在附加项之后，附加项仍作为独立元信息返回。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame original = resultFrame(4, "answer", Map.of("trace", "go-trace"));
            ResponsePayload response = codec(context).decodeResponse(request, append(original, (byte) 'N'));
            assertEquals("answer", response.getReturnData());
            assertEquals("go-trace", response.getOption("trace"));
        }
    }

    @Test
    public void dubboGoLegacyExceptionAcceptsOneNullTerminator() throws Exception {
        // 额外 NULL 不吞掉真实业务异常。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "fail", String.class, "broken");
            DubboFrame original = resultFrame(0, new IllegalStateException("compat-error"), null);
            ResponsePayload response = codec(context).decodeResponse(request, append(original, (byte) 'N'));
            assertEquals(ProtocolStatus.InvokeError, response.getStatus());
            assertEquals("compat-error", response.getFailure().getMessage());
        }
    }

    @Test
    public void dubboGoExceptionWithAttachmentsAcceptsOneNullTerminator() throws Exception {
        // 新式异常同样先读 Throwable 与附加项，再验证唯一终止标记。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "fail", String.class, "broken");
            DubboFrame original = resultFrame(3, new IllegalStateException("compat-error"), Map.of("trace", "go"));
            ResponsePayload response = codec(context).decodeResponse(request, append(original, (byte) 'N'));
            assertEquals("compat-error", response.getFailure().getMessage());
            assertEquals("go", response.getOption("trace"));
        }
    }

    @Test
    public void dubboGoLegacyNullAcceptsOneNullTerminator() throws Exception {
        // flag2 表示业务 null，随后的一字节 N 才是 SDK 终止标记。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "nullable", String.class, null);
            DubboFrame frame = append(resultFrame(2, null, null), (byte) 'N');
            ResponsePayload response = codec(context).decodeResponse(request, frame);
            assertEquals(ProtocolStatus.OK, response.getStatus());
            assertNull(response.getReturnData());
        }
    }

    @Test
    public void dubboGoNullWithAttachmentsAcceptsOneNullTerminator() throws Exception {
        // 空业务结果不会使附加项和终止标记混为一体。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "nullable", String.class, null);
            DubboFrame original = resultFrame(5, null, Map.of("trace", "go"));
            ResponsePayload response = codec(context).decodeResponse(request, append(original, (byte) 'N'));
            assertNull(response.getReturnData());
            assertEquals("go", response.getOption("trace"));
        }
    }

    @Test
    public void dubboGoProtocolFailureAcceptsOneNullTerminator() throws Exception {
        // 非 OK 响应保存错误文本，也只有一个额外 NULL 可被接受。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame original = new DubboFrame((byte) 2, (byte) 40, 123, hessian("bad request"));
            ResponsePayload response = codec(context).decodeResponse(request, append(original, (byte) 'N'));
            assertEquals("bad request", response.getFailure().getMessage());
            assertNotEquals(ProtocolStatus.OK, response.getStatus());
        }
    }

    @Test
    public void exceptionFlagRequiresThrowable() throws Exception {
        // 错误标志不能用普通字符串冒充异常对象。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "fail", String.class, "value");
            DubboFrame frame = resultFrame(0, "not throwable", null);
            assertThrows(IOException.class, () -> codec(context).decodeResponse(request, frame));
        }
    }

    @Test
    public void unknownResultFlagIsRejected() throws Exception {
        // 结果分支只接受标准定义的 0..5。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame frame = resultFrame(6, null, null);
            assertThrows(IOException.class, () -> codec(context).decodeResponse(request, frame));
        }
    }

    @Test
    public void nullPrimitiveResultIsRejected() throws Exception {
        // 无值回复不能自动变成 primitive 的默认值。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "notices");
            DubboFrame frame = resultFrame(2, null, null);
            assertThrows(IOException.class, () -> codec(context).decodeResponse(request, frame));
        }
    }

    @Test
    public void responseIdMustMatchPendingRequest() throws Exception {
        // 相关性由会话管理，编码器仍验证传来的帧属于当前调用。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame frame = resultFrame(1, "value", null);
            request.setRequestID(124);
            assertThrows(IOException.class, () -> codec(context).decodeResponse(request, frame));
        }
    }

    @Test
    public void nonHessianSerializationIdIsRejected() throws Exception {
        // ID23 为 fastjson2，不允许当作 Hessian 或文本 JSON 猜测解码。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame original = resultFrame(1, "value", null);
            DubboFrame frame = new DubboFrame((byte) 23, original.status(), 123, original.body());
            assertThrows(IOException.class, () -> codec(context).decodeResponse(request, frame));
        }
    }

    @Test
    public void unexpectedResponseDirectionIsRejected() throws Exception {
        // 请求帧不能被当作业务响应，即使正文是合法 Hessian。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame original = resultFrame(1, "value", null);
            DubboFrame frame = new DubboFrame((byte) 0xc2, (byte) 20, 123, original.body());
            assertThrows(IOException.class, () -> codec(context).decodeResponse(request, frame));
        }
    }

    @Test
    public void heartbeatResponseEchoesRequestId() throws Exception {
        // 标准 null event 心跳按请求 ID 回应，不进入业务派发。
        try (MetContext context = configuration().buildContext()) {
            DubboCodec codec = codec(context);
            DubboFrame ping = decode(codec.heartbeat(-7));
            assertNull(codec.eventValue(ping));
            DubboFrame pong = decode(codec.heartbeat(ping));
            assertFalse(pong.request());
            assertTrue(pong.event());
            assertEquals(20, pong.status());
            assertEquals(-7, pong.requestId());
            assertNull(codec.eventValue(pong));
        }
    }

    @Test
    public void dubboGoHeartbeatResponseAcceptsOneNullTerminator() throws Exception {
        // 第一个 N 是心跳内容，第二个是官方 SDK 写入的唯一终止标记。
        try (MetContext context = configuration().buildContext()) {
            DubboFrame pong = new DubboFrame((byte) 0x22, (byte) 20, 7, new byte[] { 'N', 'N' });
            assertNull(codec(context).eventValue(pong));
        }
    }

    @Test
    public void heartbeatRequestRejectsAnExtraNull() throws Exception {
        // NULL 宽限只归响应，不能扩散到入站请求。
        try (MetContext context = configuration().buildContext()) {
            DubboFrame ping = new DubboFrame((byte) 0xe2, (byte) 0, 7, new byte[] { 'N', 'N' });
            assertThrows(IOException.class, () -> codec(context).eventValue(ping));
        }
    }

    @Test
    public void readOnlyEventIsNotAHeartbeat() throws Exception {
        // R 事件由会话改变写入状态，编码器不会伪造心跳回执。
        try (MetContext context = configuration().buildContext()) {
            DubboFrame event = new DubboFrame((byte) 0xa2, (byte) 0, 2, hessian("R"));
            assertEquals("R", codec(context).eventValue(event));
            assertThrows(IOException.class, () -> codec(context).heartbeat(event));
        }
    }

    @Test
    public void normalFrameCannotBeReadAsEvent() throws Exception {
        // 事件 API 必须保持方向与标志语义。
        try (MetContext context = configuration().buildContext()) {
            DubboFrame normal = new DubboFrame((byte) 2, (byte) 20, 2, hessian(null));
            assertThrows(IOException.class, () -> codec(context).eventValue(normal));
        }
    }

    @Test
    public void decodingFailureHasBadRequestStatus() throws Exception {
        // 原始请求已关联 ID，错误回复不用重新读取畸形正文。
        try (MetContext context = configuration().buildContext()) {
            DubboFrame request = new DubboFrame((byte) 0xc2, (byte) 0, -3, new byte[0]);
            DubboFrame response = decode(codec(context).failure(request, new IOException("bad descriptor")));
            assertEquals(40, response.status());
            assertEquals(-3, response.requestId());
        }
    }

    @Test
    public void trailingResponseValuesAreRejected() throws Exception {
        // 一个结果之后不能夹带第二个未声明值。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "nullable", String.class, null);
            byte[] body = new byte[] { (byte) 0x92, 'T' };
            DubboFrame response = new DubboFrame((byte) 2, (byte) 20, 123, body);
            assertThrows(IOException.class, () -> codec(context).decodeResponse(request, response));
        }
    }

    @Test
    public void multipleResponseNullTerminatorsAreRejected() throws Exception {
        // 只兼容官方写入的一个 N，第二个额外 NULL 仍是多余值。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame frame = append(resultFrame(1, "answer", null), (byte) 'N', (byte) 'N');
            assertThrows(IOException.class, () -> codec(context).decodeResponse(request, frame));
        }
    }

    @Test
    public void valueAfterResponseNullTerminatorIsRejected() throws Exception {
        // NULL 后必须立即 EOF，不能借终止标记夹带第二个业务值。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame frame = append(resultFrame(1, "answer", null), (byte) 'N', (byte) 'T');
            assertThrows(IOException.class, () -> codec(context).decodeResponse(request, frame));
        }
    }

    @Test
    public void responseNullTerminatorCannotReplaceAttachments() throws Exception {
        // 终止标记不能把缺失的必需附加项伪装成成功响应。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame frame = append(resultFrame(4, "answer", null), (byte) 'N');
            assertThrows(IOException.class, () -> codec(context).decodeResponse(request, frame));
        }
    }

    @Test
    public void requestRejectsAResponseNullTerminator() throws Exception {
        // 普通请求继续要求附加项后立即 EOF。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "value");
            DubboFrame frame = append(decode(codec(context).encode(request)), (byte) 'N');
            assertThrows(IOException.class, () -> codec(context).decodeRequest(frame));
        }
    }

    @Test
    public void missingAttachmentMapIsRejected() throws Exception {
        // 新式结果必须提供附加项 Map，不将截断正文当作空附加项。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "nullable", String.class, null);
            DubboFrame response = new DubboFrame((byte) 2, (byte) 20, 123, new byte[] { (byte) 0x95 });
            assertThrows(IOException.class, () -> codec(context).decodeResponse(request, response));
        }
    }

    @Test
    public void maximumAppliesWhileWritingRequest() throws Exception {
        // 编码过程中检查容量，不先生成超限正文再发送。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "echo", String.class, "x".repeat(256));
            ProtocolConfig config = ProtocolConfig.newConfig("dubbo", "dubbo", Map.of("maxFrameSize", "128"));
            DubboCodec codec = new DubboCodec(config, new RouteMountIndex("tcp-0", config), context);
            assertThrows(IllegalArgumentException.class, () -> codec.encode(request));
        }
    }

    @Test
    public void maximumAppliesWhenDirectlyDecodingFrame() throws Exception {
        // 即使绕过增量帧层，编码器也使用同一正文限制。
        try (MetContext context = configuration().buildContext()) {
            ProtocolConfig config = ProtocolConfig.newConfig("dubbo", "dubbo", Map.of("maxFrameSize", "2"));
            DubboCodec codec = new DubboCodec(config, new RouteMountIndex("tcp-0", config), context);
            DubboFrame event = new DubboFrame((byte) 0xa2, (byte) 0, 2, hessian("large"));
            assertThrows(IOException.class, () -> codec.eventValue(event));
        }
    }

    @Test
    public void registryMetadataCanRepresentNativeEmptyIdentity() throws Exception {
        // 空值来自注册中心显式映射，不根据 Meteor 默认配置猜测。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            service.setMetaData("dubbo.registry.group", "");
            service.setMetaData("dubbo.registry.version", "");
            RequestPayload outgoing = request(service, "noArgs");
            DubboFrame wire = decode(codec(context).encode(outgoing));

            // 空注册版本在二进制协议中采用原生 DEFAULT_VERSION；group 仍省略。
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(wire.body()));
            input.readString();
            input.readString();
            assertEquals("0.0.0", input.readString());
            RequestPayload incoming = codec(context).decodeRequest(wire);
            assertNull(incoming.getOption("group"));
            assertEquals("0.0.0", incoming.getOption("version"));
        }
    }

    @Test
    public void emptyRequestVersionUsesNativeDefaultWireVersion() throws Exception {
        // 单次引用显式选择原生无版本服务，优先于 binding 字面版本。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "noArgs");
            request.addOption("dubbo.version", "");
            DubboFrame wire = decode(codec(context).encode(request));

            // 空 override 不能写成空 UTF，必须遵循两版 SDK 的 DEFAULT_VERSION。
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(wire.body()));
            input.readString();
            input.readString();
            assertEquals("0.0.0", input.readString());
        }
    }

    @Test
    public void nativeDefaultWireVersionMatchesExplicitEmptyRegistryIdentity() throws Exception {
        // 本地绑定仍有默认版本，元信息明确说明注册给原生 Dubbo 的版本为空。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            service.setMetaData("dubbo.registry.group", "");
            service.setMetaData("dubbo.registry.version", "");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Hessian2Output output = new Hessian2Output(bytes);
            output.writeString("2.0.2");
            output.writeString("example.Dubbo");
            output.writeString("0.0.0");
            output.writeString("noArgs");
            output.writeString("");
            output.writeObject(new LinkedHashMap<>(Map.of("version", "0.0.0")));
            output.flush();

            // 独立构造原生对端格式，而不是仅用本编码器自产自销。
            DubboFrame frame = new DubboFrame((byte) 0xc2, (byte) 0, 123, bytes.toByteArray());
            RequestPayload incoming = codec(context).decodeRequest(frame);
            assertEquals(service.getBindName(), incoming.getServiceName());
            assertEquals(service.getBindVersion(), incoming.getServiceVersion());
        }
    }

    @Test
    public void omittedNativeGroupDoesNotMatchLiteralDefaultGroup() throws Exception {
        // 普通 binding 的 default 是真实分组名，不能与原生无分组混用。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "noArgs");
            request.addOption("dubbo.group", "");
            DubboFrame wire = decode(codec(context).encode(request));
            assertThrows(IOException.class, () -> codec(context).decodeRequest(wire));
        }
    }

    @Test
    public void registryGroupOverrideDoesNotKeepOldLiteralGroupReachable() throws Exception {
        // 元信息切换线上分组后，内部绑定身份继续用于 Dispatcher，不保留旧线上入口。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "noArgs");
            DubboFrame oldIdentity = decode(codec(context).encode(request));
            service.setMetaData("dubbo.registry.group", "blue");
            assertThrows(IOException.class, () -> codec(context).decodeRequest(oldIdentity));
        }
    }

    @Test
    public void registryVersionOverrideDoesNotKeepOldLiteralVersionReachable() throws Exception {
        // 显式版本被注册中心元信息覆写后，旧版本的请求不能落入同一个提供者。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "noArgs");
            DubboFrame oldIdentity = decode(codec(context).encode(request));
            service.setMetaData("dubbo.registry.version", "2.0.0");
            assertThrows(IOException.class, () -> codec(context).decodeRequest(oldIdentity));
        }
    }

    @Test
    public void explicitWireVersionDoesNotFallBackToAnotherPublishedVersion() throws Exception {
        // 只有单个提供者也不能把显式版本不匹配当作可安全回退。
        try (MetContext context = configuration().buildContext()) {
            RequestPayload request = request(publish(context), "noArgs");
            request.addOption("dubbo.version", "2.0.0");
            DubboFrame wire = decode(codec(context).encode(request));
            assertThrows(IOException.class, () -> codec(context).decodeRequest(wire));
        }
    }

    @Test
    public void genericInvocationResolvesMultiArgumentMethod() throws Exception {
        // 原生 GenericService 只传方法名、参数类型名和 JDK 值。
        try (MetContext context = configuration().buildContext()) {
            publish(context);
            String[] types = { "java.lang.String", "int" };
            DubboFrame frame = genericRequest("combine", types, new Object[] { "hello", 3 });

            // 转交 Dispatcher 的仍是正式发布方法与已类型化参数。
            RequestPayload request = codec(context).decodeRequest(frame);
            assertEquals("combine", request.getTargetMethod());
            assertEquals(List.of("hello", 3), request.getParameterValues());
            assertEquals("true", request.getOption("dubbo.binary.generic"));
        }
    }

    @Test
    public void genericDtoMapUsesPublishedClassWithoutDefaultConstructor() throws Exception {
        // Map 中 class 字段不能指示任意类加载，方法声明是唯一的目标类型。
        try (MetContext context = configuration().buildContext()) {
            publish(context);
            Map<String, Object> person = Map.of("class", "untrusted.DoesNotExist", "name", "Ada", "age", 41);
            String[] types = { DubboInteropPerson.class.getName() };
            DubboFrame frame = genericRequest("person", types, new Object[] { person });

            // 这个 DTO 只有参数构造方法，恢复过程不要求应用增设测试构造入口。
            RequestPayload request = codec(context).decodeRequest(frame);
            DubboInteropPerson result = (DubboInteropPerson) request.getParameterValues().get(0);
            assertEquals("Ada", result.getName());
            assertEquals(41, result.getAge());
        }
    }

    @Test
    public void genericDtoResponseIsAStandardPropertyMap() throws Exception {
        // 服务实现按普通 Java DTO 返回，泛化响应由协议转换。
        try (MetContext context = configuration().buildContext()) {
            publish(context);
            String[] types = { DubboInteropPerson.class.getName() };
            Map<String, Object> person = Map.of("name", "Ada", "age", 41);
            DubboFrame invocation = genericRequest("person", types, new Object[] { person });
            RequestPayload request = codec(context).decodeRequest(invocation);
            ResponsePayload response = success(new DubboInteropPerson("Ada", 41));
            byte[] bytes = codec(context).encode(request, response);

            // 对端 GenericService 收到属性 Map 和标准 class 提示，不要求共享 DTO 类。
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(decode(bytes).body()));
            assertEquals(4, input.readInt());
            Map<?, ?> result = (Map<?, ?>) input.readObject(Map.class);
            assertEquals("Ada", result.get("name"));
            assertEquals(41, result.get("age"));
            assertEquals(DubboInteropPerson.class.getName(), result.get("class"));
        }
    }

    @Test
    public void genericTypeNamesCannotLoadAnUnpublishedClass() throws Exception {
        // 网络类型名只参与方法匹配，未发布的签名立即失败。
        try (MetContext context = configuration().buildContext()) {
            publish(context);
            String[] types = { "untrusted.DoesNotExist" };
            DubboFrame frame = genericRequest("person", types, new Object[] { Map.of("name", "Ada") });
            IOException failure = assertThrows(IOException.class, () -> codec(context).decodeRequest(frame));
            assertTrue(failure.getMessage().contains("Unknown Dubbo generic method"));
        }
    }

    @Test
    public void genericParameterTypeCountMustMatchValues() throws Exception {
        // 类型列表与值列表是同一次调用的两个维度，不能静默截断。
        try (MetContext context = configuration().buildContext()) {
            publish(context);
            DubboFrame frame = genericRequest("combine", new String[] { "java.lang.String" }, new Object[] { "a", 1 });
            assertThrows(IOException.class, () -> codec(context).decodeRequest(frame));
        }
    }

    @Test
    public void genericListRestoresDtoElementsFromPublishedType() throws Exception {
        // 原生泛化调用传入 List<Map>，方法声明要求 List<Person>。
        try (MetContext context = configuration().buildContext()) {
            publish(context);
            List<Object> people = List.of(Map.of("name", "Ada", "age", 41));
            DubboFrame frame = genericRequest("people", new String[] { "java.util.List" }, new Object[] { people });

            // raw List 相容不能跳过元素恢复，否则服务端会收到 Map 而非 DTO。
            RequestPayload request = codec(context).decodeRequest(frame);
            List<?> values = (List<?>) request.getParameterValues().get(0);
            DubboInteropPerson result = (DubboInteropPerson) values.get(0);
            assertEquals("Ada", result.getName());
            assertEquals(41, result.getAge());
        }
    }

    @Test
    public void genericRawMapKeepsJdkValuesWithoutForeignTypeLoading() throws Exception {
        // Map<String,Object> 的 Object 值保持数据，不解释不受信任的 class 提示。
        try (MetContext context = configuration().buildContext()) {
            publish(context);
            Map<String, Object> fields = Map.of("person", Map.of("class", "unknown.Foreign", "name", "Ada"));
            DubboFrame frame = genericRequest("properties", new String[] { "java.util.Map" }, new Object[] { fields });

            // 只按本地声明的 Object 类型交付嵌套 Map。
            RequestPayload request = codec(context).decodeRequest(frame);
            Map<?, ?> restored = (Map<?, ?>) request.getParameterValues().get(0);
            Map<?, ?> person = (Map<?, ?>) restored.get("person");
            assertEquals("unknown.Foreign", person.get("class"));
            assertEquals("Ada", person.get("name"));
        }
    }

    @Test
    public void genericListResultGeneralizesEachDto() throws Exception {
        // 服务返回值仍是普通 List<Person>，不要求业务实现理解 GenericService。
        try (MetContext context = configuration().buildContext()) {
            publish(context);
            List<Object> people = List.of(Map.of("name", "Ada", "age", 41));
            DubboFrame frame = genericRequest("people", new String[] { "java.util.List" }, new Object[] { people });
            RequestPayload request = codec(context).decodeRequest(frame);
            ResponsePayload response = success(List.of(new DubboInteropPerson("Ada", 41)));
            byte[] encoded = codec(context).encode(request, response);

            // 每个 DTO 元素独立携带属性和 class 提示。
            Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(decode(encoded).body()));
            assertEquals(4, input.readInt());
            List<?> values = (List<?>) input.readObject(List.class);
            Map<?, ?> person = (Map<?, ?>) values.get(0);
            assertEquals("Ada", person.get("name"));
            assertEquals(DubboInteropPerson.class.getName(), person.get("class"));
        }
    }

    @Test
    public void servicePublishedAtAnotherEndpointIsNotSelectedByItsDubboAlias() throws Exception {
        // 本地服务只通过正式配置中的 tcp-0 端点发布。
        try (MetContext context = configuration().buildContext()) {
            MetBindInfo<?> service = publish(context);
            RequestPayload request = request(service, "echo", String.class, "endpoint");
            DubboFrame frame = decode(codec(context).encode(request));

            // 相同协议和服务别名不能绕过当前端点的发布范围。
            ProtocolConfig config = ProtocolConfig.newConfig("dubbo", "dubbo", Map.of());
            RouteMountIndex routes = new RouteMountIndex("another-endpoint", config);
            DubboCodec otherEndpoint = new DubboCodec(config, routes, context);
            IOException failure = assertThrows(IOException.class, () -> otherEndpoint.decodeRequest(frame));
            assertTrue(failure.getMessage().contains("Unknown Dubbo service"));
        }
    }

    private static MetConfiguration configuration() {
        MetConfiguration config = new MetConfiguration().setDefaultProtocol("dubbo");
        config.connector("tcp").protocol("dubbo");
        return config;
    }

    @Test
    public void genericInvocationResolvesAnInheritedMethodParameter() throws Exception {
        // 正式发布类型把父接口的 T 绑定为无默认构造方法的 DTO。
        try (MetContext context = configuration().buildContext()) {
            context.publisher().rsfService(PersonEcho.class, value -> value) //
                    .aliasName("dubbo", "example.Dubbo").serialize("Hessian2").register();
            Map<String, Object> person = Map.of("name", "Ada", "age", 41);
            DubboFrame frame = genericRequest("echo", new String[] { Object.class.getName() }, //
                    new Object[] { person });

            // JVM 派发仍采用擦除签名，参数值按公共描述恢复为真实 DTO。
            RequestPayload request = codec(context).decodeRequest(frame);
            assertEquals(Object.class.getName(), request.getParameterTypes().get(0));
            DubboInteropPerson value = (DubboInteropPerson) request.getParameterValues().get(0);
            assertEquals("Ada", value.getName());
        }
    }

    @Test
    public void genericInvocationResolvesAnInheritedDtoField() throws Exception {
        // DTO 字段继承自 Holder<T>，具体类型把 T 绑定为 Person。
        try (MetContext context = configuration().buildContext()) {
            context.publisher().rsfService(HolderEcho.class, value -> value) //
                    .aliasName("dubbo", "example.Dubbo").serialize("Hessian2").register();
            Map<String, Object> holder = Map.of("item", Map.of("name", "Ada", "age", 41));
            DubboFrame frame = genericRequest("echo", new String[] { PersonHolder.class.getName() }, //
                    new Object[] { holder });

            // 内层 Map 不能停留在 Object 字段中，应按属性的公共泛型描述恢复。
            RequestPayload request = codec(context).decodeRequest(frame);
            PersonHolder value = (PersonHolder) request.getParameterValues().get(0);
            assertEquals("Ada", value.item.getName());
        }
    }

    public interface GenericEcho<T> {
        T echo(T value);
    }

    public interface PersonEcho extends GenericEcho<DubboInteropPerson> {
    }

    public interface HolderEcho {
        PersonHolder echo(PersonHolder value);
    }

    public static class Holder<T> {
        public T item;
    }

    public static class PersonHolder extends Holder<DubboInteropPerson> {
    }

    private static MetBindInfo<?> publish(MetContext context) {
        return context.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                .aliasName("dubbo", "example.Dubbo").serialize("Hessian2").register();
    }

    private static DubboCodec codec(MetContext context) {
        ProtocolConfig config = ProtocolConfig.newConfig("dubbo", "dubbo", Map.of());
        return new DubboCodec(config, new RouteMountIndex("tcp-0", config), context);
    }

    private static RequestPayload request(MetBindInfo<?> service, String method, Object... arguments) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(123);
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(method);
        request.setClientTimeout(3000);
        request.setSerializeType("Hessian2");
        request.addOption("dubbo", "2.0.2");
        for (int index = 0; index < arguments.length; index += 2) {
            Class<?> type = (Class<?>) arguments[index];
            request.addParameter(type.getName(), arguments[index + 1]);
        }
        return request;
    }

    private static ResponsePayload success(Object value) {
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        return response;
    }

    private static DubboFrame decode(byte[] bytes) throws IOException {
        return new DubboFrameDecoder(DubboCodec.DEFAULT_MAX_FRAME_SIZE).readFrame(ByteBuffer.wrap(bytes));
    }

    private static DubboFrame resultFrame(int flag, Object value, Map<String, Object> attachments) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.setSerializerFactory(new DubboHessianSerializerFactory(DubboCodecTest.class.getClassLoader()));
        output.writeInt(flag);
        if (flag == 0 || flag == 1 || flag == 3 || flag == 4) {
            output.writeObject(value);
        }
        if (attachments != null) {
            output.writeObject(new LinkedHashMap<>(attachments));
        }
        output.flush();
        return new DubboFrame((byte) 2, (byte) 20, 123, bytes.toByteArray());
    }

    private static DubboFrame append(DubboFrame frame, byte... trailing) {
        byte[] body = Arrays.copyOf(frame.body(), frame.body().length + trailing.length);
        System.arraycopy(trailing, 0, body, frame.body().length, trailing.length);
        return new DubboFrame(frame.flags(), frame.status(), frame.requestId(), body);
    }

    private static DubboFrame genericRequest(String method, String[] types, Object[] values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.setSerializerFactory(new DubboHessianSerializerFactory(DubboCodecTest.class.getClassLoader()));
        output.writeString("2.0.2");
        output.writeString("example.Dubbo");
        output.writeString("1.0.0");
        output.writeString("$invoke");
        output.writeString("Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/Object;");
        output.writeObject(method);
        output.writeObject(types);
        output.writeObject(values);
        output.writeObject(Map.of("generic", "true", "group", "default", "version", "1.0.0"));
        output.flush();
        return new DubboFrame((byte) 0xc2, (byte) 0, 123, bytes.toByteArray());
    }

    private static DubboFrame versionedRequest(String protocolVersion, String attachedVersion) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.writeString(protocolVersion);
        output.writeString("example.Dubbo");
        output.writeString("1.0.0");
        output.writeString("echo");
        output.writeString("Ljava/lang/String;");
        output.writeString("value");
        Map<String, Object> attachments = new LinkedHashMap<>();
        attachments.put("group", "default");
        attachments.put("version", "1.0.0");
        attachments.put("dubbo", attachedVersion);
        output.writeObject(attachments);
        output.flush();
        return new DubboFrame((byte) 0xc2, (byte) 0, 123, bytes.toByteArray());
    }

    private static byte[] hessian(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.writeObject(value);
        output.flush();
        return bytes.toByteArray();
    }
}
