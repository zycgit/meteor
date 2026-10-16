/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.ContentType;
import net.hasor.meteor.protocol.soap.codec.SoapAttachmentCodec.Message;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static net.hasor.meteor.protocol.soap.definition.SoapVersion.SOAP_11;
import static net.hasor.meteor.protocol.soap.definition.SoapVersion.SOAP_12;
import static org.junit.Assert.*;

public class SoapAttachmentCodecTest {
    private static final String ENVELOPE_11      = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String ENVELOPE_12      = "http://www.w3.org/2003/05/soap-envelope";
    private static final String XOP              = "http://www.w3.org/2004/08/xop/include";
    private static final String BOUNDARY         = "soap-test-boundary";
    private static final String RELATED_TYPE     = "multipart/related; boundary=\"" + BOUNDARY + "\"";
    private static final String XOP_PACKAGE_TYPE = RELATED_TYPE + "; type=\"application/xop+xml\"";
    private static final String MTOM_TYPE        = XOP_PACKAGE_TYPE + "; start-info=\"text/xml\"";
    private static final String SWA_TYPE         = RELATED_TYPE + "; type=\"text/xml\"";
    private static final int    MAX_BYTES        = 64 * 1024;

    @Test
    public void mtomSoap11RoundTripPreservesRawBinaryAndMediaType() throws Exception {
        // 用零字节及非文本字节构造明确标记的二进制元素。
        byte[] expected = { 0, (byte) 0xff, 0x10, 0x20, (byte) 0x80 };
        Document source = document(envelope(ENVELOPE_11, Base64.getEncoder().encodeToString(expected)));

        // SOAP 1.1 的 MTOM 包保留 text/xml 根类型，附件携带原始字节。
        Message encoded = SoapAttachmentCodec.encode(source, Map.of(data(source), expected), SOAP_11, //
                "urn:test:upload", true, MAX_BYTES);
        ContentType packageType = new ContentType(encoded.contentType());
        ContentType startInfo = new ContentType(packageType.getParameter("start-info"));
        assertEquals("multipart/related", packageType.getBaseType());
        assertEquals("application/xop+xml", packageType.getParameter("type"));
        assertEquals("text/xml", startInfo.getBaseType());
        assertNull(startInfo.getParameter("action"));
        assertNotNull(packageType.getParameter("start"));
        String wire = new String(encoded.body(), StandardCharsets.ISO_8859_1);
        assertTrue(wire.contains(new String(expected, StandardCharsets.ISO_8859_1)));

        // 解码还原 Base64 XML，业务字节与输入完全一致。
        Message decoded = SoapAttachmentCodec.decode(encoded.body(), encoded.contentType(), SOAP_11, MAX_BYTES);
        assertEquals("text/xml", new ContentType(decoded.contentType()).getBaseType());
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void mtomSoap12RoundTripPreservesActionAndBinary() throws Exception {
        // SOAP 1.2 将 action 放入媒体类型，值包含需要引用的字符。
        byte[] expected = { (byte) 0xfe, 0, 0x01, 0x02 };
        String action = "urn:test:upload?mode=binary;v=2";
        Document source = document(envelope(ENVELOPE_12, Base64.getEncoder().encodeToString(expected)));

        // 包级 start-info 与还原后的 SOAP 媒体类型都保留原始 action。
        Message encoded = SoapAttachmentCodec.encode(source, Map.of(data(source), expected), SOAP_12, //
                action, true, MAX_BYTES);
        ContentType packageType = new ContentType(encoded.contentType());
        ContentType startInfo = new ContentType(packageType.getParameter("start-info"));
        assertEquals("application/xop+xml", packageType.getParameter("type"));
        assertEquals("application/soap+xml", startInfo.getBaseType());
        assertEquals(action, startInfo.getParameter("action"));
        String wire = new String(encoded.body(), StandardCharsets.ISO_8859_1);
        assertTrue(wire.contains(new String(expected, StandardCharsets.ISO_8859_1)));

        Message decoded = SoapAttachmentCodec.decode(encoded.body(), encoded.contentType(), SOAP_12, MAX_BYTES);
        ContentType decodedType = new ContentType(decoded.contentType());
        assertEquals("application/soap+xml", decodedType.getBaseType());
        assertEquals(action, decodedType.getParameter("action"));
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void mtomSoap12LongActionRemainsSingleQuotedHttpParameter() throws Exception {
        // 使用超过邮件建议行宽且带分号的 action，要求 HTTP 参数完整保留并正确引用。
        byte[] expected = { 0, (byte) 0xff, 1, 2 };
        String action = "urn:example:attachment-service:upload-binary-document:version-2026;mode=preserve-action";
        Document source = document(envelope(ENVELOPE_12, Base64.getEncoder().encodeToString(expected)));

        // 经 MTOM 编码和解码后，HTTP 媒体类型不能使用邮件参数续段拆分 action。
        Message encoded = SoapAttachmentCodec.encode(source, Map.of(data(source), expected), SOAP_12, //
                action, true, MAX_BYTES);
        Message decoded = SoapAttachmentCodec.decode(encoded.body(), encoded.contentType(), SOAP_12, MAX_BYTES);
        String contentType = decoded.contentType();
        assertTrue(contentType.contains("action=\"" + action + "\""));
        assertFalse(contentType.contains("action*"));
        assertEquals(action, new ContentType(contentType).getParameter("action"));
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void swaRoundTripRestoresHrefBinary() throws Exception {
        // SwA 使用元素上的 href 引用附件。
        byte[] expected = { 0, (byte) 0xf0, 0x12, 0x34 };
        Document source = document(envelope(ENVELOPE_11, Base64.getEncoder().encodeToString(expected)));

        // 编码产物是以 text/xml 为根的 multipart/related 包。
        Message encoded = SoapAttachmentCodec.encode(source, Map.of(data(source), expected), SOAP_11, //
                null, false, MAX_BYTES);
        ContentType packageType = new ContentType(encoded.contentType());
        assertEquals("multipart/related", packageType.getBaseType());
        assertEquals("text/xml", packageType.getParameter("type"));
        String wire = new String(encoded.body(), StandardCharsets.ISO_8859_1);
        assertTrue(wire.contains("href=\"cid:"));
        assertFalse(wire.contains("xop:Include"));

        // 还原后不残留附件 href，业务数据恢复成 Base64 文本。
        Message decoded = SoapAttachmentCodec.decode(encoded.body(), encoded.contentType(), SOAP_11, MAX_BYTES);
        Document restored = document(decoded.body());
        assertFalse(data(restored).hasAttribute("href"));
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void ordinaryBase64StringIsNotExtractedAsAttachment() throws Exception {
        // 字符串内容恰好是 Base64，但没有被业务类型标记为二进制。
        String value = "AP8QIA==";
        Document source = document(envelope(ENVELOPE_11, value));

        // 仅凭字符串形状不能生成 MIME 附件。
        Message encoded = SoapAttachmentCodec.encode(source, Map.of(), SOAP_11, null, true, MAX_BYTES);
        Document restored = document(encoded.body());
        assertEquals("text/xml", new ContentType(encoded.contentType()).getBaseType());
        assertEquals(value, data(restored).getTextContent());
        assertEquals(0, restored.getElementsByTagNameNS(XOP, "Include").getLength());
    }

    @Test
    public void encodingDoesNotMutateSourceDocument() throws Exception {
        // 保存原始 DOM 副本，保留二进制节点上的业务属性。
        byte[] bytes = { 1, 2, 3 };
        Document source = document(envelope(ENVELOPE_11, "AQID"));
        Element original = data(source);
        original.setAttribute("label", "source");
        Document before = (Document) source.cloneNode(true);

        // MTOM 只在内部副本中替换内容，调用方 DOM 仍可继续使用。
        SoapAttachmentCodec.encode(source, Map.of(original, bytes), SOAP_11, null, true, MAX_BYTES);
        assertTrue(before.isEqualNode(source));
        assertEquals("AQID", original.getTextContent());
        assertFalse(original.hasAttribute("href"));
        assertEquals(0, source.getElementsByTagNameNS(XOP, "Include").getLength());
    }

    @Test
    public void startSelectsRootAfterBinaryPart() throws Exception {
        // 将附件放在第一段，通过 start 指向后面的 XML 根段。
        byte[] expected = { 0, (byte) 0xff, 0x20 };
        byte[] attachment = binaryPart("binary@example", "binary", expected);
        byte[] root = xopRoot(xopEnvelope("cid:binary@example"));
        byte[] body = multipart(attachment, root);
        String contentType = MTOM_TYPE + "; start=\"<root@example>\"";

        // 根选择由 start 决定，不能尝试把第一段二进制解析为 XML。
        Message decoded = SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void absentStartUsesFirstPartAsRoot() throws Exception {
        // MIME 类型不声明 start，第一段即为根 XML。
        byte[] expected = { 3, 2, 1 };
        byte[] root = xopRoot(xopEnvelope("cid:binary@example"));
        byte[] attachment = binaryPart("binary@example", "binary", expected);
        byte[] body = multipart(root, attachment);

        // 按默认根规则解析并还原附件。
        Message decoded = SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, MAX_BYTES);
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void duplicateContentIdIsRejected() throws Exception {
        // 两个附件声明同一 Content-ID，使引用目标不唯一。
        byte[] root = xopRoot(xopEnvelope("cid:duplicate@example"));
        byte[] first = binaryPart("duplicate@example", "binary", new byte[] { 1 });
        byte[] second = binaryPart("duplicate@example", "binary", new byte[] { 2 });
        byte[] body = multipart(root, first, second);

        // 拒绝歧义引用，不能按先后顺序静默选取附件。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, MAX_BYTES);
        });
        assertTrue(failure.getMessage().contains("Duplicate SOAP attachment Content-ID"));
    }

    @Test
    public void missingReferencedPartIsRejected() throws Exception {
        // XML 引用的 CID 在当前 MIME 包内不存在。
        byte[] body = multipart(xopRoot(xopEnvelope("cid:missing@example")));

        // 缺少附件时给出明确失败，不保留未解析的 xop:Include。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, MAX_BYTES);
        });
        assertTrue(failure.getMessage().contains("no local MIME part"));
    }

    @Test
    public void xopRejectsRemoteHrefEvenWithMatchingLocalLocation() throws Exception {
        // 当前包含同地址的附件，但 XOP 引用仍必须使用 cid 方案。
        String location = "https://example.invalid/binary";
        byte[] root = xopRoot(xopEnvelope(location));
        String headers = String.join("\r\n", //
                "Content-Type: application/octet-stream", //
                "Content-Location: " + location, //
                "Content-Transfer-Encoding: binary");
        byte[] body = multipart(root, part(headers, new byte[] { 1, 2 }));

        // 不把非 CID 的 XOP 地址当作可加载的网络资源。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, MAX_BYTES);
        });
        assertTrue(failure.getMessage().contains("must identify a local MIME part"));
    }

    @Test
    public void truncatedClosingBoundaryIsRejected() throws Exception {
        // 保留各段内容，移除 MIME 最后的关闭分界线。
        byte[] root = xopRoot(xopEnvelope("cid:binary@example"));
        byte[] attachment = binaryPart("binary@example", "binary", new byte[] { 1 });
        byte[] complete = multipart(root, attachment);
        int closingBytes = ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII).length;
        byte[] truncated = Arrays.copyOf(complete, complete.length - closingBytes);

        // 严格 MIME 解析必须拒绝不完整消息。
        MessagingException failure = assertThrows(MessagingException.class, () -> {
            SoapAttachmentCodec.decode(truncated, MTOM_TYPE, SOAP_11, MAX_BYTES);
        });
        assertTrue(failure.getMessage().contains("boundary"));
    }

    @Test
    public void duplicateBoundaryParameterIsRejectedIgnoringCase() throws Exception {
        // 第二个同名参数使用大写，不能绕过参数唯一性检查。
        byte[] body = multipart(xopRoot(envelope(ENVELOPE_11, "AQID")));
        String contentType = MTOM_TYPE + "; BOUNDARY=\"another-boundary\"";

        // 拒绝歧义参数，不接受 MIME 库覆盖后的最后一个值。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        });
        assertTrue(failure.getMessage().contains("Ambiguous SOAP MIME Content-Type parameter: boundary"));
    }

    @Test
    public void rfc2231UnencodedStartInfoSegmentsCanArriveOutOfOrder() throws Exception {
        // start-info 按序号拼接，参数在头部的出现顺序不决定结果。
        byte[] expected = { 1, 2, 3 };
        byte[] root = xopRoot(xopEnvelope("cid:binary@example"));
        byte[] attachment = binaryPart("binary@example", "binary", expected);
        byte[] body = multipart(root, attachment);
        String startInfo = "start-info*1=xml; start-info*0=\"text/\"";
        String contentType = XOP_PACKAGE_TYPE + "; " + startInfo;

        // 未编码续段按 0、1 顺序还原为 text/xml，附件仍可正常解析。
        Message decoded = SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        assertEquals("text/xml", new ContentType(decoded.contentType()).getBaseType());
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void rfc2231EncodedAndUnencodedTypeSegmentsAreCombined() throws Exception {
        // 第 0 段声明字符集并编码斜杠，第 1 段是普通未编码文本。
        byte[] root = part("Content-Type: text/xml; charset=UTF-8", ascii(envelope(ENVELOPE_11, "AQID")));
        byte[] body = multipart(root);
        String contentType = RELATED_TYPE + "; type*0*=UTF-8''text%2F; type*1=xml";

        // 合法混合编码续段还原成根媒体类型，不被误判为重复参数。
        Message decoded = SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        assertEquals("text/xml", new ContentType(decoded.contentType()).getBaseType());
        assertArrayEquals(new byte[] { 1, 2, 3 }, binary(decoded.body()));
    }

    @Test
    public void rfc2231StandaloneExtendedTypeIsDecoded() throws Exception {
        // 独立扩展参数携带字符集和百分号编码，不需要续段编号。
        byte[] root = part("Content-Type: text/xml; charset=UTF-8", ascii(envelope(ENVELOPE_11, "AQID")));
        byte[] body = multipart(root);
        String contentType = RELATED_TYPE + "; type*=UTF-8''text%2Fxml";

        // type* 的值按 RFC 2231 解码后参与根媒体类型校验。
        Message decoded = SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        assertEquals("text/xml", new ContentType(decoded.contentType()).getBaseType());
        assertArrayEquals(new byte[] { 1, 2, 3 }, binary(decoded.body()));
    }

    @Test
    public void rfc2231DuplicateContinuationIndexIsRejected() throws Exception {
        // 相同序号出现两次，即使值完全相同也无法构成唯一续段。
        byte[] root = part("Content-Type: text/xml; charset=UTF-8", ascii(envelope(ENVELOPE_11, "AQID")));
        byte[] body = multipart(root);
        String contentType = RELATED_TYPE + "; type*0=\"text/\"; type*0=\"text/\"; type*1=xml";

        // 拒绝重复序号，不能依赖 MIME 库最后写入的值。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        });
        String message = failure.getMessage();
        assertTrue(message, message.contains("Ambiguous") || message.contains("Invalid"));
    }

    @Test
    public void rfc2231EncodedAndUnencodedFormsCannotReuseContinuationIndex() throws Exception {
        // 编码标记不属于序号，type*0 与 type*0* 仍指向同一段。
        byte[] root = part("Content-Type: text/xml; charset=UTF-8", ascii(envelope(ENVELOPE_11, "AQID")));
        byte[] body = multipart(root);
        String contentType = RELATED_TYPE + "; type*0=\"text/\"; type*0*=UTF-8''text%2F; type*1=xml";

        // 不能通过编码标记绕过重复序号检查。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        });
        String message = failure.getMessage();
        assertTrue(message, message.contains("Ambiguous") || message.contains("Invalid"));
    }

    @Test
    public void rfc2231ContinuationWithoutIndexZeroIsRejected() throws Exception {
        // 续段必须从 0 开始，单独的第 1 段不能作为完整值。
        byte[] root = part("Content-Type: text/xml; charset=UTF-8", ascii(envelope(ENVELOPE_11, "AQID")));
        byte[] body = multipart(root);
        String contentType = RELATED_TYPE + "; type*1=xml";

        // 缺少起始段时直接拒绝，不把残缺参数当作缺省配置。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        });
        String message = failure.getMessage();
        assertTrue(message, message.contains("Ambiguous") || message.contains("Invalid"));
    }

    @Test
    public void rfc2231ContinuationWithMissingMiddleIndexIsRejected() throws Exception {
        // 0 与 2 之间缺少第 1 段，剩余文本不能直接拼接。
        byte[] root = part("Content-Type: text/xml; charset=UTF-8", ascii(envelope(ENVELOPE_11, "AQID")));
        byte[] body = multipart(root);
        String contentType = RELATED_TYPE + "; type*0=\"text/\"; type*2=xml";

        // 拒绝序号断档，防止不同解析器采用不同拼接结果。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        });
        String message = failure.getMessage();
        assertTrue(message, message.contains("Ambiguous") || message.contains("Invalid"));
    }

    @Test
    public void rfc2231SimpleAndExtendedTypeCannotBeMixed() throws Exception {
        // 同时声明 type 与 type*，即使解码值相同也存在两个来源。
        byte[] root = part("Content-Type: text/xml; charset=UTF-8", ascii(envelope(ENVELOPE_11, "AQID")));
        byte[] body = multipart(root);
        String contentType = RELATED_TYPE + "; type=\"text/xml\"; type*=UTF-8''text%2Fxml";

        // 拒绝普通参数与独立扩展参数混用。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        });
        String message = failure.getMessage();
        assertTrue(message, message.contains("Ambiguous") || message.contains("Invalid"));
    }

    @Test
    public void rfc2231SimpleAndContinuedTypeCannotBeMixed() throws Exception {
        // 同时声明完整 type 和由第 0、1 段拼接的 type。
        byte[] root = part("Content-Type: text/xml; charset=UTF-8", ascii(envelope(ENVELOPE_11, "AQID")));
        byte[] body = multipart(root);
        String contentType = RELATED_TYPE + "; type=\"text/xml\"; type*0=\"text/\"; type*1=xml";

        // 完整值不能与续段值共存，避免覆盖规则影响根类型选择。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        });
        String message = failure.getMessage();
        assertTrue(message, message.contains("Ambiguous") || message.contains("Invalid"));
    }

    @Test
    public void rfc2231StandaloneExtendedAndContinuedTypeCannotBeMixed() throws Exception {
        // 独立扩展值与扩展续段同样是同一参数的两个完整来源。
        byte[] root = part("Content-Type: text/xml; charset=UTF-8", ascii(envelope(ENVELOPE_11, "AQID")));
        byte[] body = multipart(root);
        String contentType = RELATED_TYPE + "; type*=UTF-8''text%2Fxml; type*0*=UTF-8''text%2F; type*1=xml";

        // 扩展编码不能掩盖独立值与续段值之间的歧义。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, contentType, SOAP_11, MAX_BYTES);
        });
        String message = failure.getMessage();
        assertTrue(message, message.contains("Ambiguous") || message.contains("Invalid"));
    }

    @Test
    public void rawMultipartSizeOverLimitIsRejected() throws Exception {
        // 完整原始实体只比限制多一个字节。
        byte[] root = xopRoot(xopEnvelope("cid:binary@example"));
        byte[] attachment = binaryPart("binary@example", "binary", new byte[] { 1, 2, 3 });
        byte[] body = multipart(root, attachment);
        int maximum = body.length - 1;

        // 在解析 MIME 之前限制 HTTP 实体大小。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, maximum);
        });
        assertTrue(failure.getMessage().contains("exceeds maximum size " + maximum));
    }

    @Test
    public void expandedBase64SizeOverLimitIsRejected() throws Exception {
        // 原始 MIME 可容纳，但 4 KiB 二进制展开成 Base64 后超过同一限额。
        byte[] bytes = new byte[4096];
        Arrays.fill(bytes, (byte) 0xa5);
        byte[] root = xopRoot(xopEnvelope("cid:binary@example"));
        byte[] attachment = binaryPart("binary@example", "binary", bytes);
        byte[] body = multipart(root, attachment);
        int maximum = body.length + 64;
        assertTrue(body.length < maximum);

        // 还原 XML 时继续检查展开后的大小，不能只检查传输字节。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, maximum);
        });
        assertTrue(failure.getMessage().contains("exceeds maximum size " + maximum));
    }

    @Test
    public void base64TransferEncodingRestoresOriginalBytes() throws Exception {
        // 手写 Base64 MIME 段，明确给出预期二进制而非复用编码器作为期望。
        byte[] expected = { 0, (byte) 0xff, 0x10, 0x20 };
        byte[] root = xopRoot(xopEnvelope("cid:binary@example"));
        byte[] attachment = binaryPart("binary@example", "base64", ascii("AP8Q\r\nIA=="));
        byte[] body = multipart(root, attachment);

        // MIME 传输编码先解码，再将实际字节写入 SOAP Base64 元素。
        Message decoded = SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, MAX_BYTES);
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void quotedPrintableTransferEncodingRestoresOriginalBytes() throws Exception {
        // 手写 quoted-printable，包括高位字节和软换行。
        byte[] expected = { 0, (byte) 0xff, 0x10, 0x20 };
        byte[] root = xopRoot(xopEnvelope("cid:binary@example"));
        byte[] attachment = binaryPart("binary@example", "quoted-printable", ascii("=00=FF=\r\n=10=20"));
        byte[] body = multipart(root, attachment);

        // 软换行不进入业务数据，转义字节按传输编码还原。
        Message decoded = SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, MAX_BYTES);
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void cidPercentEscapesAreDecodedOnce() throws Exception {
        // CID 中的百分号由 %25 表示，解码后不能再把 %40 解成 @。
        byte[] expected = { 8, 6, 4 };
        byte[] root = xopRoot(xopEnvelope("cid:binary%2540value%40example"));
        byte[] attachment = binaryPart("binary%40value@example", "binary", expected);
        byte[] body = multipart(root, attachment);

        // URI 解码一次后与 Content-ID 原文精确匹配。
        Message decoded = SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, MAX_BYTES);
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void cidPlusSignRemainsLiteral() throws Exception {
        // CID 不是表单参数，加号不得被转换为空格。
        byte[] expected = { 7, 5, 3 };
        byte[] root = xopRoot(xopEnvelope("cid:binary+value@example"));
        byte[] attachment = binaryPart("binary+value@example", "binary", expected);
        byte[] body = multipart(root, attachment);

        // 按字面加号找到当前包附件。
        Message decoded = SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, MAX_BYTES);
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void swaResolvesContentLocationWithinCurrentPackage() throws Exception {
        // XML 引用相对于根地址解析，并与本包附件的绝对位置匹配。
        byte[] expected = { 9, 8, 7 };
        String xml = envelope(ENVELOPE_11, "").replace("<data>", "<data href=\"../assets/data.bin\">");
        String rootHeaders = String.join("\r\n", //
                "Content-Type: text/xml; charset=UTF-8", //
                "Content-ID: <root@example>", //
                "Content-Location: https://example.invalid/messages/root.xml");
        String binaryHeaders = String.join("\r\n", //
                "Content-Type: application/octet-stream", //
                "Content-Location: https://example.invalid/assets/data.bin", //
                "Content-Transfer-Encoding: binary");
        byte[] body = multipart(part(rootHeaders, ascii(xml)), part(binaryHeaders, expected));

        // 只通过当前 MIME 包的 Content-Location 解析相对引用。
        Message decoded = SoapAttachmentCodec.decode(body, SWA_TYPE, SOAP_11, MAX_BYTES);
        Document restored = document(decoded.body());
        assertFalse(data(restored).hasAttribute("href"));
        assertArrayEquals(expected, binary(decoded.body()));
    }

    @Test
    public void swaRejectsExternalHrefMissingFromCurrentPackage() throws Exception {
        // 当前包内没有与该绝对地址对应的 Content-Location。
        String xml = envelope(ENVELOPE_11, "").replace("<data>", //
                "<data href=\"https://example.invalid/not-in-package.bin\">");
        byte[] body = multipart(part("Content-Type: text/xml; charset=UTF-8", ascii(xml)));

        // 外部地址只用作本地查找键，未命中时直接拒绝。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, SWA_TYPE, SOAP_11, MAX_BYTES);
        });
        assertTrue(failure.getMessage().contains("no local MIME part"));
    }

    @Test
    public void conflictingSoap12ActionsAreRejected() throws Exception {
        // 根段 type 与包级 start-info 声明不同 SOAP 1.2 action。
        String xml = envelope(ENVELOPE_12, "AQID");
        String rootType = "application/xop+xml; type=\"application/soap+xml; action=\\\"urn:root\\\"\"";
        String rootHeaders = "Content-Type: " + rootType + "\r\nContent-ID: <root@example>";
        String startInfo = "start-info=\"application/soap+xml; action=\\\"urn:package\\\"\"";
        String contentType = XOP_PACKAGE_TYPE + "; " + startInfo;
        byte[] body = multipart(part(rootHeaders, ascii(xml)));

        // 拒绝冲突，避免 MIME 解析层与 SOAP 调用层采用不同 action。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, contentType, SOAP_12, MAX_BYTES);
        });
        assertTrue(failure.getMessage().contains("Conflicting SOAP action parameters"));
    }

    @Test
    public void packageTypeMustMatchSelectedRootType() throws Exception {
        // 包声明 XOP 根，实际第一段却是普通 text/xml。
        byte[] root = part("Content-Type: text/xml; charset=UTF-8", ascii(envelope(ENVELOPE_11, "AQID")));
        byte[] body = multipart(root);

        // 拒绝根媒体类型不匹配的消息。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, MAX_BYTES);
        });
        assertTrue(failure.getMessage().contains("root does not match the package type"));
    }

    @Test
    public void repeatedXopReferenceToSamePartIsRejected() throws Exception {
        // 两个元素都指向同一附件，违反 MTOM 二进制段的一次引用约束。
        String include = "<xop:Include href=\"cid:binary@example\"/>";
        String xml = envelope(ENVELOPE_11, include).replace("</data>", "</data><other>" + include + "</other>");
        byte[] attachment = binaryPart("binary@example", "binary", new byte[] { 1, 2, 3 });
        byte[] body = multipart(xopRoot(xml), attachment);

        // 第二个引用必须失败，不能重复展开同一附件。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.decode(body, MTOM_TYPE, SOAP_11, MAX_BYTES);
        });
        assertTrue(failure.getMessage().contains("must be referenced exactly once"));
    }

    @Test
    public void binaryPayloadOverEncodingLimitIsRejected() throws Exception {
        // 二进制内容本身已经超过限额，与 MIME 边界开销无关。
        byte[] bytes = new byte[1025];
        Document source = document(envelope(ENVELOPE_11, Base64.getEncoder().encodeToString(bytes)));
        Map<Element, byte[]> binaries = Map.of(data(source), bytes);

        // 编码前累计二进制大小并拒绝超限数据。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapAttachmentCodec.encode(source, binaries, SOAP_11, null, true, 1024);
        });
        assertTrue(failure.getMessage().contains("exceeds maximum size 1024"));
    }

    private static String envelope(String namespace, String content) {
        String envelopeStart = "<s:Envelope xmlns:s=\"" + namespace + "\" xmlns:xop=\"" + XOP + "\">";
        String body = "<s:Body><data>" + content + "</data></s:Body>";
        return envelopeStart + body + "</s:Envelope>";
    }

    private static String xopEnvelope(String href) {
        return envelope(ENVELOPE_11, "<xop:Include href=\"" + href + "\"/>");
    }

    private static Document document(String xml) throws Exception {
        return document(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static Document document(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static Element data(Document document) {
        return (Element) document.getElementsByTagName("data").item(0);
    }

    private static byte[] binary(byte[] xml) throws Exception {
        return Base64.getDecoder().decode(data(document(xml)).getTextContent());
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] xopRoot(String xml) {
        String headers = String.join("\r\n", //
                "Content-Type: application/xop+xml; type=\"text/xml\"; charset=UTF-8", //
                "Content-ID: <root@example>", //
                "Content-Transfer-Encoding: 8bit");
        return part(headers, ascii(xml));
    }

    private static byte[] binaryPart(String id, String encoding, byte[] bytes) {
        String headers = String.join("\r\n", //
                "Content-Type: application/octet-stream", //
                "Content-ID: <" + id + ">", //
                "Content-Transfer-Encoding: " + encoding);
        return part(headers, bytes);
    }

    private static byte[] part(String headers, byte[] body) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ascii(headers + "\r\n\r\n"));
        output.writeBytes(body);
        return output.toByteArray();
    }

    private static byte[] multipart(byte[]... parts) {
        // 仅拼装明确给出的 MIME 段，不使用待测编码器生成输入。
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            output.writeBytes(ascii("--" + BOUNDARY + "\r\n"));
            output.writeBytes(part);
            output.writeBytes(ascii("\r\n"));
        }
        output.writeBytes(ascii("--" + BOUNDARY + "--\r\n"));
        return output.toByteArray();
    }
}
