/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.protocol.rsf;
import java.io.IOException;
import net.hasor.meteor.connector.protocol.rsf.codec.RsfV1Codec;
import net.hasor.meteor.connector.protocol.rsf.codec.TestWireCodec;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import org.junit.Test;
import static net.hasor.meteor.unit.connector.protocol.rsf.support.RsfFixtures.fixture;
import static net.hasor.meteor.unit.connector.protocol.rsf.support.RsfFixtures.serializationContext;
import static org.junit.Assert.*;

/** RSF/1 历史报文与序列化边界的单元覆盖。 */
public class RsfCodecTest {
    private final RsfV1Codec codec = new RsfV1Codec(serializationContext());

    @Test
    public void originalRequestFixtureDecodesAndReencodesByteForByte() throws Exception {
        // 原始 RSF/1 报文经正式编解码入口后仍保持逐字节兼容。
        byte[] fixture = fixture("request-v1.bin");
        RequestPayload request = (RequestPayload) this.codec.decode(fixture);
        assertEquals(42, request.getRequestID());
        assertEquals("Echo", request.getServiceName());
        assertEquals("中文🙂", request.getParameterValues().get(0));
        assertEquals(0x1234, request.getFlags());
        assertEquals("legacy", request.getOption("trace"));
        assertArrayEquals(fixture, this.codec.encode(request));
    }

    @Test
    public void legacyResponseWireReencodesByteForByte() throws Exception {
        // 历史响应含重复返回值，底层报文结构必须保持原有字节布局。
        byte[] legacy = fixture("response-v1.bin");
        byte[] reencoded = TestWireCodec.reencodeBody(legacy);
        assertArrayEquals(legacy, reencoded);
    }

    @Test
    public void legacyResponseRemainsReadableWithoutDuplicatingReturnData() throws Exception {
        // 旧响应中的重复返回值仍可正常读取。
        byte[] fixture = fixture("response-v1.bin");
        ResponsePayload response = (ResponsePayload) this.codec.decode(fixture);
        assertEquals("中文🙂", response.getReturnData());
        assertEquals(42, response.getRequestID());

        // 正常编码只保留一份返回值数据。
        byte[] encoded = this.codec.encode(response);
        int duplicatedReturnBytes = TestWireCodec.returnDataSize(fixture) + 4;
        assertEquals(fixture.length - duplicatedReturnBytes, encoded.length);

        // 通过会话实际使用的编解码入口验证响应字段与选项。
        ResponsePayload decoded = (ResponsePayload) this.codec.decode(encoded);
        assertEquals(response.getRequestID(), decoded.getRequestID());
        assertEquals(response.getStatus(), decoded.getStatus());
        assertEquals(response.getSerializeType(), decoded.getSerializeType());
        assertEquals(response.getReturnType(), decoded.getReturnType());
        assertEquals(response.getReturnData(), decoded.getReturnData());
        for (String key : response.getOptionKeys()) {
            assertEquals(response.getOption(key), decoded.getOption(key));
        }
    }

    @Test
    public void unsignedParameterCountRoundTrips() throws Exception {
        // 参数数量超过有符号 byte 上限，准备 200 个参数。
        RequestPayload request = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
        for (int i = 1; i < 200; i++) {
            request.addParameter("java.lang.String", "v" + i);
        }

        // 按无符号数量读取参数，最后一个参数不能丢失。
        byte[] encoded = this.codec.encode(request);
        RequestPayload decoded = (RequestPayload) this.codec.decode(encoded);
        assertEquals(200, decoded.getParameterValues().size());
        assertEquals("v199", decoded.getParameterValues().get(199));
    }

    @Test
    public void unsignedOptionCountRoundTrips() throws Exception {
        // 历史 trace 选项加 199 个选项，越过有符号 byte 上限。
        RequestPayload request = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
        for (int i = 1; i < 200; i++) {
            request.addOption("key" + i, "value" + i);
        }

        // 按无符号数量读取选项，保留完整选项数量和尾部数据。
        byte[] encoded = this.codec.encode(request);
        RequestPayload decoded = (RequestPayload) this.codec.decode(encoded);
        assertEquals(request.getOptionKeys().length, decoded.getOptionKeys().length);
        assertEquals("value199", decoded.getOption("key199"));
    }

    @Test
    public void parameterCountAboveUnsignedByteLimitIsRejected() throws Exception {
        // RSF/1 数量字段最多表示 255 个参数，256 个参数必须拒绝编码。
        RequestPayload request = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
        for (int i = 1; i <= 255; i++) {
            request.addParameter("java.lang.String", "overflow");
        }

        // 超过协议数量上限时明确拒绝编码。
        try {
            this.codec.encode(request);
            fail("Parameter count exceeds the RSF/1 limit");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Too many RSF parameters"));
        }
    }

    @Test
    public void unknownRequestSerializerRejectsBusinessParameters() throws Exception {
        // 请求带有业务参数，但声明了不存在的序列化器。
        RequestPayload request = (RequestPayload) this.codec.decode(fixture("request-v1.bin"));
        request.setSerializeType("missing");

        // 必须报告缺失的序列化器，不能悄悄丢弃参数。
        IOException failure = assertThrows(IOException.class, () -> this.codec.encode(request));
        assertTrue(failure.getMessage().contains("missing"));
    }

    @Test
    public void unknownResponseSerializerRejectsBusinessResult() {
        // 响应包含非空业务返回值。
        ResponsePayload response = response("must not disappear");
        response.setSerializeType("missing");

        // 必须报告缺失的序列化器，不能把返回值编码成空数据。
        IOException failure = assertThrows(IOException.class, () -> this.codec.encode(response));
        assertTrue(failure.getMessage().contains("missing"));
    }

    @Test
    public void unknownResponseSerializerRejectsTypedNullResult() {
        // 即使返回值为 null，声明了返回类型也需要对应的序列化器。
        ResponsePayload response = response(null);
        response.setSerializeType("missing");

        IOException failure = assertThrows(IOException.class, () -> this.codec.encode(response));
        assertTrue(failure.getMessage().contains("missing"));
    }

    @Test
    public void serializationErrorWithoutBusinessResultCanBeEncoded() throws Exception {
        // 协议错误仅携带状态和选项，不需要业务返回值的序列化器。
        ResponsePayload response = response(null);
        response.setSerializeType("missing");
        response.setReturnType(null);
        response.setStatus(ProtocolStatus.SerializeForbidden);
        response.addOption("message", "unsupported serializer");

        // 错误原因仍能通过协议抵达调用方。
        byte[] encoded = this.codec.encode(response);
        ResponsePayload decoded = (ResponsePayload) this.codec.decode(encoded);
        assertEquals(ProtocolStatus.SerializeForbidden, decoded.getStatus());
        assertEquals("unsupported serializer", decoded.getOption("message"));
    }

    @Test
    public void failedSerializationDoesNotPreventNextEncoding() throws Exception {
        // 先触发一次响应序列化失败。
        ResponsePayload response = response("recovered");
        response.setSerializeType("missing");
        assertThrows(IOException.class, () -> this.codec.encode(response));

        // 更正序列化器后，同一编解码器仍能完整返回业务数据。
        response.setSerializeType("Java");
        response.setStatus(ProtocolStatus.SerializeForbidden);
        response.addOption("message", "unsupported serializer");
        byte[] encoded = this.codec.encode(response);
        ResponsePayload recovered = (ResponsePayload) this.codec.decode(encoded);
        assertEquals("recovered", recovered.getReturnData());
        assertEquals(ProtocolStatus.SerializeForbidden, recovered.getStatus());
    }

    private ResponsePayload response(String result) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(42);
        response.setSerializeType("Java");
        response.setStatus(ProtocolStatus.OK);
        response.setReturnType(String.class.getName());
        response.setReturnData(result);
        return response;
    }
}
