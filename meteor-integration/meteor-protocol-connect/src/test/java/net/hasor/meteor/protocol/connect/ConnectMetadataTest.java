/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.domain.OptionInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectMetadataTest {
    @Test
    public void standaloneMetadataStartsEmpty() {
        // 独立创建的元数据尚未附加任何消息字段。
        ConnectMetadata metadata = ConnectMetadata.create();
        assertTrue(metadata.getHeaders().isEmpty());
        assertTrue(metadata.getTrailers().isEmpty());
    }

    @Test
    public void headerNamesAreCaseInsensitive() {
        // 写入和读取时统一大小写，不改变字段值。
        ConnectMetadata metadata = ConnectMetadata.create();
        metadata.addHeader("X-Request-Id", "first");
        assertEquals(List.of("first"), metadata.getHeader("X-REQUEST-ID"));
    }

    @Test
    public void repeatedHeaderKeepsInsertionOrder() {
        // 同名头部允许多个值，并保持原有顺序。
        ConnectMetadata metadata = ConnectMetadata.create();
        metadata.addHeader("x-region", "east");
        metadata.addHeader("x-region", "west");
        assertEquals(List.of("east", "west"), metadata.getHeader("x-region"));
    }

    @Test
    public void repeatedTrailerKeepsInsertionOrder() {
        // 响应尾部也保留同名多值。
        ConnectMetadata metadata = ConnectMetadata.create();
        metadata.addTrailer("operation-cost", "5");
        metadata.addTrailer("operation-cost", "8");
        assertEquals(List.of("5", "8"), metadata.getTrailer("OPERATION-COST"));
    }

    @Test
    public void changesAreStoredInTheBorrowedMessage() {
        // 元数据视图修改原消息选项，新视图应读取到同一结果。
        OptionInfo message = new OptionInfo();
        ConnectMetadata.from(message).addHeader("authorization", "Bearer test");
        assertEquals(List.of("Bearer test"), ConnectMetadata.from(message).getHeader("authorization"));
    }

    @Test
    public void absentHeaderReturnsAnEmptyList() {
        // 缺失字段以空列表表示，不返回 null。
        ConnectMetadata metadata = ConnectMetadata.create();
        assertEquals(List.of(), metadata.getHeader("unknown"));
    }

    @Test
    public void absentTrailerReturnsAnEmptyList() {
        // 尾部字段尚未到达时，读取结果为空。
        ConnectMetadata metadata = ConnectMetadata.create();
        assertEquals(List.of(), metadata.getTrailer("unknown"));
    }

    @Test
    public void binaryHeaderUsesUnpaddedBase64() {
        // 二进制值在线上使用无填充 Base64，同时支持还原原字节。
        ConnectMetadata metadata = ConnectMetadata.create();
        byte[] value = { 0, 1, -1 };
        metadata.addBinaryHeader("trace-bin", value);
        assertEquals(List.of("AAH/"), metadata.getHeader("trace-bin"));
        assertArrayEquals(value, metadata.getBinaryHeader("TRACE-BIN").get(0));
    }

    @Test
    public void binaryTrailerUsesUnpaddedBase64() {
        // 尾部的二进制值遵循同一编码约定。
        ConnectMetadata metadata = ConnectMetadata.create();
        byte[] value = { 1 };
        metadata.addBinaryTrailer("result-bin", value);
        assertEquals(List.of("AQ"), metadata.getTrailer("result-bin"));
        assertArrayEquals(value, metadata.getBinaryTrailer("result-bin").get(0));
    }

    @Test
    public void readsPaddedBinaryMetadata() {
        // 对端发送带填充 Base64 时仍能解码。
        OptionInfo options = new OptionInfo();
        options.addOption("connect-metadata-headers", "{\"trace-bin\":[\"AQ==\"]}");
        assertArrayEquals(new byte[] { 1 }, ConnectMetadata.from(options).getBinaryHeader("trace-bin").get(0));
    }

    @Test
    public void rejectsMalformedBinaryMetadata() {
        // 非法 Base64 不能静默变为空字节。
        OptionInfo options = new OptionInfo();
        options.addOption("connect-metadata-headers", "{\"trace-bin\":[\"%%%\"]}");
        assertThrows(IllegalArgumentException.class, () -> ConnectMetadata.from(options).getBinaryHeader("trace-bin"));
    }

    @Test
    public void headerSnapshotCannotBeModified() {
        // 返回快照不能直接修改，避免绕过字段校验。
        ConnectMetadata metadata = ConnectMetadata.create();
        metadata.addHeader("x-region", "east");
        Map<String, List<String>> snapshot = metadata.getHeaders();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x-other", List.of("value")));
    }

    @Test
    public void headerValuesCannotBeModified() {
        // 快照中的列表同样只读。
        ConnectMetadata metadata = ConnectMetadata.create();
        metadata.addHeader("x-region", "east");
        List<String> values = metadata.getHeader("x-region");
        assertThrows(UnsupportedOperationException.class, () -> values.add("west"));
    }

    @Test
    public void previousSnapshotDoesNotChangeAfterAppending() {
        // 后续追加不能改变已经取得的快照。
        ConnectMetadata metadata = ConnectMetadata.create();
        metadata.addHeader("x-region", "east");
        Map<String, List<String>> snapshot = metadata.getHeaders();
        metadata.addHeader("x-region", "west");
        assertEquals(List.of("east"), snapshot.get("x-region"));
    }

    @Test
    public void rejectsReservedConnectHeader() {
        // connect- 前缀属于协议字段，业务不能覆盖。
        ConnectMetadata metadata = ConnectMetadata.create();
        assertThrows(IllegalArgumentException.class, () -> metadata.addHeader("connect-timeout-ms", "10"));
    }

    @Test
    public void rejectsHttpContentTypeAsBusinessMetadata() {
        // 业务元数据不能替换 HTTP 正文类型。
        ConnectMetadata metadata = ConnectMetadata.create();
        assertThrows(IllegalArgumentException.class, () -> metadata.addHeader("Content-Type", "application/json"));
    }

    @Test
    public void rejectsHeaderNameContainingSpaces() {
        // 字段名只接受协议允许的字符。
        ConnectMetadata metadata = ConnectMetadata.create();
        assertThrows(IllegalArgumentException.class, () -> metadata.addHeader("bad name", "value"));
    }

    @Test
    public void textApiRejectsBinaryHeaderNames() {
        // 二进制字段必须经过二进制 API，避免误用文本。
        ConnectMetadata metadata = ConnectMetadata.create();
        assertThrows(IllegalArgumentException.class, () -> metadata.addHeader("trace-bin", "value"));
    }

    @Test
    public void binaryApiRequiresTheBinarySuffix() {
        // 二进制字段以 -bin 标识。
        ConnectMetadata metadata = ConnectMetadata.create();
        assertThrows(IllegalArgumentException.class, () -> metadata.addBinaryHeader("trace", new byte[] { 1 }));
    }

    @Test
    public void rejectsNewlineInTextValue() {
        // 文本值不允许注入下一条 HTTP 头。
        ConnectMetadata metadata = ConnectMetadata.create();
        assertThrows(IllegalArgumentException.class, () -> metadata.addHeader("trace", "first\r\nsecond"));
    }

    @Test
    public void rejectsNonAsciiTextValue() {
        // 非 ASCII 内容应使用二进制字段，而不是普通头。
        ConnectMetadata metadata = ConnectMetadata.create();
        assertThrows(IllegalArgumentException.class, () -> metadata.addHeader("trace", "中文"));
    }

    @Test
    public void rejectsMalformedHeadersOption() {
        // 选项不是 JSON 对象时，读取方明确报告格式错误。
        OptionInfo options = new OptionInfo();
        options.addOption("connect-metadata-headers", "[");
        assertThrows(IllegalArgumentException.class, () -> ConnectMetadata.from(options).getHeaders());
    }

    @Test
    public void rejectsNullTrailersOption() {
        // JSON null 不能替代字段对象。
        OptionInfo options = new OptionInfo();
        options.addOption("connect-metadata-trailers", "null");
        assertThrows(IllegalArgumentException.class, () -> ConnectMetadata.from(options).getTrailers());
    }
}
