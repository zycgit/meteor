/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.hasor.example.protocol.thrift.generated.InteropService;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.*;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.junit.Test;
import static org.junit.Assert.*;

public class ThriftWireCodecTest {
    @Test
    public void binaryEncodingUsesStrictHeaderAndOfficialBody() throws Exception {
        // 使用生成代码的正文，验证 Meteor 输出可由官方严格 Binary 读取。
        ThriftWireCodec codec = this.codec();
        InteropService.echo_args arguments = new InteropService.echo_args("中文消息");
        byte[] data = codec.encode("binary", "echo", TMessageType.CALL, 17, arguments);

        // 严格消息头和正文均符合官方协议。
        assertEquals(0x80, data[0] & 0xff);
        TProtocol protocol = new TBinaryProtocol(new TMemoryInputTransport(data), true, true);
        TMessage header = protocol.readMessageBegin();
        InteropService.echo_args decoded = new InteropService.echo_args();
        decoded.read(protocol);
        protocol.readMessageEnd();
        assertEquals("echo", header.name);
        assertEquals(TMessageType.CALL, header.type);
        assertEquals(17, header.seqid);
        assertEquals("中文消息", decoded.getValue());
    }

    @Test
    public void compactEncodingIsReadableByOfficialProtocol() throws Exception {
        // Compact 输出保留方法、序列号及生成类正文。
        ThriftWireCodec codec = this.codec();
        byte[] data = codec.encode("compact", "echo", TMessageType.CALL, -7, new InteropService.echo_args("compact"));

        // 使用独立官方解码器验证输出，避免只检验自身往返。
        TProtocol protocol = new TCompactProtocol(new TMemoryInputTransport(data));
        TMessage header = protocol.readMessageBegin();
        InteropService.echo_args decoded = new InteropService.echo_args();
        decoded.read(protocol);
        protocol.readMessageEnd();
        assertEquals("echo", header.name);
        assertEquals(-7, header.seqid);
        assertEquals("compact", decoded.getValue());
    }

    @Test
    public void jsonEncodingIsReadableByOfficialProtocol() throws Exception {
        // JSON 字符串包含转义和非 ASCII 内容。
        ThriftWireCodec codec = this.codec();
        String value = "中文\n\"quote\"\\slash";
        byte[] data = codec.encode("json", "echo", TMessageType.CALL, 9, new InteropService.echo_args(value));

        // 官方 JSON 解码恢复原始字符与消息身份。
        TProtocol protocol = new TJSONProtocol(new TMemoryInputTransport(data));
        TMessage header = protocol.readMessageBegin();
        InteropService.echo_args decoded = new InteropService.echo_args();
        decoded.read(protocol);
        protocol.readMessageEnd();
        assertEquals("echo", header.name);
        assertEquals(9, header.seqid);
        assertEquals(value, decoded.getValue());
    }

    @Test
    public void decodesOfficialStrictBinaryMessage() throws Exception {
        // 对端使用官方严格 Binary 生成空参数调用。
        TMemoryBuffer output = new TMemoryBuffer(64);
        TProtocol protocol = new TBinaryProtocol(output, true, true);
        this.writePing(protocol, 21);
        byte[] data = this.bytes(output);

        // 自动识别编码，并保留完整原始消息供后续正文读取。
        ThriftMessage message = this.codec().decode(data);
        assertEquals("binary", message.encoding());
        assertEquals("ping", message.methodName());
        assertEquals(TMessageType.CALL, message.messageType());
        assertEquals(21, message.sequenceId());
        assertArrayEquals(data, message.data());
    }

    @Test
    public void decodesOfficialLegacyBinaryMessage() throws Exception {
        // 旧 Binary 不写版本头，由官方协议明确关闭严格写入。
        TMemoryBuffer output = new TMemoryBuffer(64);
        TProtocol protocol = new TBinaryProtocol(output, false, false);
        this.writePing(protocol, 22);
        byte[] data = this.bytes(output);

        // 旧格式归入 Binary 编码，同时保留其原始消息字节。
        ThriftMessage message = this.codec().decode(data);
        assertEquals("binary", message.encoding());
        assertEquals("ping", message.methodName());
        assertEquals(22, message.sequenceId());
        assertArrayEquals(data, message.data());
    }

    @Test
    public void decodesOfficialCompactMessage() throws Exception {
        // 官方 Compact 消息作为独立输入。
        TMemoryBuffer output = new TMemoryBuffer(64);
        this.writePing(new TCompactProtocol(output), 23);
        byte[] data = this.bytes(output);

        // 自动识别 Compact 的消息头。
        ThriftMessage message = this.codec().decode(data);
        assertEquals("compact", message.encoding());
        assertEquals("ping", message.methodName());
        assertEquals(23, message.sequenceId());
        assertArrayEquals(data, message.data());
    }

    @Test
    public void decodesOfficialJsonMessage() throws Exception {
        // 官方 JSON 消息作为独立输入。
        TMemoryBuffer output = new TMemoryBuffer(64);
        this.writePing(new TJSONProtocol(output), 24);
        byte[] data = this.bytes(output);

        // 自动识别 JSON，并保留方法及序列号。
        ThriftMessage message = this.codec().decode(data);
        assertEquals("json", message.encoding());
        assertEquals("ping", message.methodName());
        assertEquals(24, message.sequenceId());
        assertArrayEquals(data, message.data());
    }

    @Test
    public void encodesApplicationExceptionWithOfficialBody() throws Exception {
        // 异常输出使用 EXCEPTION 消息类型与标准异常正文。
        ThriftWireCodec codec = this.codec();
        TApplicationException failure = new TApplicationException(TApplicationException.UNKNOWN_METHOD, "missing");
        byte[] data = codec.encodeException("binary", "unknown", 25, failure);

        // 官方解码器能读取错误码、原因和对应请求序列号。
        TProtocol protocol = new TBinaryProtocol(new TMemoryInputTransport(data));
        TMessage header = protocol.readMessageBegin();
        TApplicationException decoded = TApplicationException.readFrom(protocol);
        protocol.readMessageEnd();
        assertEquals(TMessageType.EXCEPTION, header.type);
        assertEquals("unknown", header.name);
        assertEquals(25, header.seqid);
        assertEquals(TApplicationException.UNKNOWN_METHOD, decoded.getType());
        assertEquals("missing", decoded.getMessage());
    }

    @Test
    public void binaryDecodeRejectsTruncatedBody() throws Exception {
        // 消息头完整，但正文的 STOP 字节尚未到达。
        byte[] data = this.ping("binary", 1);
        byte[] truncated = Arrays.copyOf(data, data.length - 1);

        // 一次性解码不能把不完整正文当作成功消息。
        assertThrows(TException.class, () -> this.codec().decode(truncated));
    }

    @Test
    public void compactDecodeRejectsTruncatedBody() throws Exception {
        // Compact 正文缺少终止字节。
        byte[] data = this.ping("compact", 1);
        byte[] truncated = Arrays.copyOf(data, data.length - 1);

        // 一次性解码报告截断。
        assertThrows(TException.class, () -> this.codec().decode(truncated));
    }

    @Test
    public void jsonDecodeRejectsTruncatedBody() throws Exception {
        // JSON 缺少最外层消息数组的右括号。
        byte[] data = this.ping("json", 1);
        byte[] truncated = Arrays.copyOf(data, data.length - 1);

        // 一次性解码报告截断。
        assertThrows(TException.class, () -> this.codec().decode(truncated));
    }

    @Test
    public void binaryDecodeRejectsTrailingGarbage() throws Exception {
        // 一条完整 Binary 消息之后还有未归属的字节。
        byte[] data = this.withTrailingByte(this.ping("binary", 1));

        // 严格一次性解码要求整个输入只包含一条消息。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void compactDecodeRejectsTrailingGarbage() throws Exception {
        // 一条完整 Compact 消息之后还有未归属的字节。
        byte[] data = this.withTrailingByte(this.ping("compact", 1));

        // 严格一次性解码拒绝尾随内容。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void jsonDecodeRejectsTrailingGarbage() throws Exception {
        // 一条完整 JSON 消息之后追加非空白垃圾。
        byte[] data = this.withTrailingByte(this.ping("json", 1));

        // JSON 的数组闭合不能掩盖剩余内容。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void binaryScanStopsAtFirstMessage() throws Exception {
        // 流式扫描需要识别粘在一起的两条 Binary 消息边界。
        byte[] first = this.ping("binary", 1);
        byte[] joined = this.join(first, this.ping("binary", 2));

        // 返回第一条消息占用的准确字节数。
        assertEquals(first.length, this.codec().scan(joined, 0, joined.length));
    }

    @Test
    public void compactScanStopsAtFirstMessage() throws Exception {
        // 两条 Compact 消息共用同一接收缓冲区。
        byte[] first = this.ping("compact", 1);
        byte[] joined = this.join(first, this.ping("compact", 2));

        // 扫描仅消费第一条消息。
        assertEquals(first.length, this.codec().scan(joined, 0, joined.length));
    }

    @Test
    public void jsonScanStopsAtFirstMessage() throws Exception {
        // 两条 JSON 消息直接拼接，不依赖换行分隔。
        byte[] first = this.ping("json", 1);
        byte[] joined = this.join(first, this.ping("json", 2));

        // 扫描定位第一条消息的数组结尾。
        assertEquals(first.length, this.codec().scan(joined, 0, joined.length));
    }

    @Test
    public void scanHonorsOffsetAndAvailableLength() throws Exception {
        // 输入片段前后均有不属于当前消息的数据。
        byte[] data = this.ping("binary", 3);
        byte[] surrounding = this.join(new byte[] { 99, 98 }, this.join(data, new byte[] { 97 }));

        // offset 与 length 限定待扫描区间，返回值仍是已消费长度。
        assertEquals(data.length, this.codec().scan(surrounding, 2, data.length));
    }

    @Test
    public void binaryScanReturnsIncompleteForEveryTruncatedPrefix() throws Exception {
        // 枚举同一 Binary 消息的所有不完整前缀。
        byte[] data = this.ping("binary", 4);
        ThriftWireCodec codec = this.codec();

        // 所有前缀都需要继续收包，不提前输出消息。
        for (int length = 0; length < data.length; length++) {
            assertEquals("prefix length " + length, 0, codec.scan(data, 0, length));
        }
    }

    @Test
    public void compactScanReturnsIncompleteForEveryTruncatedPrefix() throws Exception {
        // 枚举同一 Compact 消息的所有不完整前缀。
        byte[] data = this.ping("compact", 4);
        ThriftWireCodec codec = this.codec();

        // 不完整变长整数和正文均只报告需要更多输入。
        for (int length = 0; length < data.length; length++) {
            assertEquals("prefix length " + length, 0, codec.scan(data, 0, length));
        }
    }

    @Test
    public void jsonScanReturnsIncompleteForEveryTruncatedPrefix() throws Exception {
        // 枚举同一 JSON 消息的所有不完整前缀。
        byte[] data = this.ping("json", 4);
        ThriftWireCodec codec = this.codec();

        // 只有最外层消息完整闭合后才允许输出。
        for (int length = 0; length < data.length; length++) {
            assertEquals("prefix length " + length, 0, codec.scan(data, 0, length));
        }
    }

    @Test
    public void jsonRejectsSequenceIdOverflow() {
        // 官方 JSON 的窄类型转换不能使超范围序列号回绕。
        byte[] data = this.json("[1,\"ping\",1,2147483648,{}]");

        // 序列号必须严格落在 i32 范围。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void jsonRejectsByteOverflow() {
        // i8 正文数值超出上界。
        byte[] data = this.json("[1,\"ping\",1,1,{\"1\":{\"i8\":128}}]");

        // 跳过未知字段时也必须校验原始数值范围。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void jsonRejectsI16Overflow() {
        // i16 正文数值超出上界。
        byte[] data = this.json("[1,\"ping\",1,1,{\"1\":{\"i16\":32768}}]");

        // 跳过未知字段不能将数值窄化为负数。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void jsonRejectsI32Overflow() {
        // i32 正文数值超出上界。
        byte[] data = this.json("[1,\"ping\",1,1,{\"1\":{\"i32\":2147483648}}]");

        // 溢出属于协议错误。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void jsonRejectsI64Overflow() {
        // i64 正文数值超出上界。
        byte[] data = this.json("[1,\"ping\",1,1,{\"1\":{\"i64\":9223372036854775808}}]");

        // 解析异常统一通过 Thrift 异常契约向调用者报告。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void jsonRejectsNegativeListSize() {
        // list 声明负元素数量，即使没有实际元素也非法。
        byte[] data = this.json("[1,\"ping\",1,1,{\"1\":{\"lst\":[\"i32\",-1]}}]");

        // 容器校验必须在读取元素之前拒绝负值。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void jsonRejectsNegativeSetSize() {
        // set 声明负元素数量。
        byte[] data = this.json("[1,\"ping\",1,1,{\"1\":{\"set\":[\"i32\",-1]}}]");

        // set 与 list 使用一致的非负大小约束。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void jsonRejectsNegativeMapSize() {
        // map 声明负条目数量。
        byte[] data = this.json("[1,\"ping\",1,1,{\"1\":{\"map\":[\"str\",\"i32\",-1,{}]}}]");

        // 空对象不能使负数量成为合法 map。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void jsonRejectsDuplicateStructFieldIds() {
        // 同一 struct 对象重复出现相同字段 ID。
        byte[] data = this.json("[1,\"ping\",1,1,{\"1\":{\"i32\":1},\"1\":{\"i32\":2}}]");

        // 保留重复字段信息，禁止解析器静默覆盖。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void jsonRejectsDuplicateMapKeys() {
        // Thrift map 的 JSON 对象含有重复字符串键。
        String content = "[1,\"ping\",1,1,{\"1\":{\"map\":[\"str\",\"i32\",2,{\"key\":1,\"key\":2}]}}]";
        byte[] data = this.json(content);

        // 不以最后一个值覆盖前一个值。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void binaryRejectsNegativeStringLength() throws Exception {
        // 官方 Binary 写入器可构造声明长度为负数的故障正文。
        TMemoryBuffer output = new TMemoryBuffer(64);
        TProtocol protocol = new TBinaryProtocol(output);
        protocol.writeMessageBegin(new TMessage("ping", TMessageType.CALL, 1));
        protocol.writeStructBegin(new TStruct("args"));
        protocol.writeFieldBegin(new TField("value", TType.STRING, (short) 1));
        protocol.writeI32(-1);
        byte[] data = this.bytes(output);

        // 不等待不可能完成的正文，也不接受负长度字符串。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void binaryRejectsNegativeContainerSize() throws Exception {
        // 故障 list 的元素数量为负值。
        TMemoryBuffer output = new TMemoryBuffer(64);
        TProtocol protocol = new TBinaryProtocol(output);
        protocol.writeMessageBegin(new TMessage("ping", TMessageType.CALL, 1));
        protocol.writeStructBegin(new TStruct("args"));
        protocol.writeFieldBegin(new TField("value", TType.LIST, (short) 1));
        protocol.writeListBegin(new TList(TType.I32, -1));
        byte[] data = this.bytes(output);

        // 容器约束在缺少后续字节时也可立即判定。
        assertThrows(TException.class, () -> this.codec().decode(data));
    }

    @Test
    public void decodeRejectsMessageAboveConfiguredLength() throws Exception {
        // 使用正常编解码器生成超出当前接收上限的消息。
        InteropService.echo_args arguments = new InteropService.echo_args("a".repeat(128));
        byte[] data = this.codec().encode("binary", "echo", TMessageType.CALL, 1, arguments);
        ThriftWireCodec bounded = new ThriftWireCodec(64, 128, 32);

        // 整条消息大小受上限约束。
        assertThrows(TException.class, () -> bounded.decode(data));
    }

    @Test
    public void encodeRejectsMessageAboveConfiguredLength() {
        // 发送路径使用相同的消息长度边界。
        ThriftWireCodec bounded = new ThriftWireCodec(64, 128, 32);
        InteropService.echo_args arguments = new InteropService.echo_args("a".repeat(128));

        // 生成正文不能绕过输出消息上限。
        assertThrows(TException.class, () -> bounded.encode("binary", "echo", TMessageType.CALL, 1, arguments));
    }

    @Test
    public void binaryRejectsContainerAboveConfiguredCount() throws Exception {
        // 消息长度很小，但 list 的条目数超过独立容器上限。
        InteropService.echoList_args arguments = new InteropService.echoList_args(List.of("a", "b", "c"));
        byte[] data = this.codec().encode("binary", "echoList", TMessageType.CALL, 1, arguments);
        ThriftWireCodec bounded = new ThriftWireCodec(1024, 2, 32);

        // 不能只依赖总报文长度来保护容器数量。
        assertThrows(TException.class, () -> bounded.decode(data));
    }

    @Test
    public void compactRejectsContainerAboveConfiguredCount() throws Exception {
        // Compact 的短容器头也必须应用独立数量限制。
        InteropService.echoList_args arguments = new InteropService.echoList_args(List.of("a", "b", "c"));
        byte[] data = this.codec().encode("compact", "echoList", TMessageType.CALL, 1, arguments);
        ThriftWireCodec bounded = new ThriftWireCodec(1024, 2, 32);

        // 协议编码差异不改变容器上限。
        assertThrows(TException.class, () -> bounded.decode(data));
    }

    @Test
    public void jsonRejectsContainerAboveConfiguredCount() throws Exception {
        // JSON 容器声明的数量超过限制。
        InteropService.echoList_args arguments = new InteropService.echoList_args(List.of("a", "b", "c"));
        byte[] data = this.codec().encode("json", "echoList", TMessageType.CALL, 1, arguments);
        ThriftWireCodec bounded = new ThriftWireCodec(1024, 2, 32);

        // 即使实际元素齐全，也不允许超过数量边界。
        assertThrows(TException.class, () -> bounded.decode(data));
    }

    @Test
    public void binaryRejectsStructNestingAboveConfiguredDepth() throws Exception {
        // 正文包含多层未知 struct 字段，需要递归跳过。
        TMemoryBuffer output = new TMemoryBuffer(128);
        TProtocol protocol = new TBinaryProtocol(output);
        protocol.writeMessageBegin(new TMessage("ping", TMessageType.CALL, 1));
        this.writeNestedStruct(protocol, 4);
        protocol.writeMessageEnd();
        byte[] data = this.bytes(output);
        ThriftWireCodec bounded = new ThriftWireCodec(1024, 128, 2);

        // 深度限制保护未知字段扫描路径。
        assertThrows(TException.class, () -> bounded.decode(data));
    }

    @Test
    public void compactRejectsStructNestingAboveConfiguredDepth() throws Exception {
        // 使用官方 Compact 写入多层未知 struct。
        TMemoryBuffer output = new TMemoryBuffer(128);
        TProtocol protocol = new TCompactProtocol(output);
        protocol.writeMessageBegin(new TMessage("ping", TMessageType.CALL, 1));
        this.writeNestedStruct(protocol, 4);
        protocol.writeMessageEnd();
        byte[] data = this.bytes(output);
        ThriftWireCodec bounded = new ThriftWireCodec(1024, 128, 2);

        // Compact 的字段 ID 压缩不改变递归层数边界。
        assertThrows(TException.class, () -> bounded.decode(data));
    }

    @Test
    public void jsonRejectsStructNestingAboveConfiguredDepth() throws Exception {
        // 使用官方 JSON 写入多层未知 struct。
        TMemoryBuffer output = new TMemoryBuffer(128);
        TProtocol protocol = new TJSONProtocol(output);
        protocol.writeMessageBegin(new TMessage("ping", TMessageType.CALL, 1));
        this.writeNestedStruct(protocol, 4);
        protocol.writeMessageEnd();
        byte[] data = this.bytes(output);
        ThriftWireCodec bounded = new ThriftWireCodec(1024, 128, 2);

        // JSON 的语法括号和 Thrift 语义深度都不能绕过限制。
        assertThrows(TException.class, () -> bounded.decode(data));
    }

    @Test
    public void binaryDecodesUuidListFromOfficialWriter() throws Exception {
        // UUID 作为容器元素，由官方 Binary 生成消息。
        UUID first = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        UUID second = UUID.fromString("fedcba98-7654-3210-ffff-0123456789ab");
        TMemoryBuffer output = new TMemoryBuffer(128);
        this.writeUuidList(new TBinaryProtocol(output), first, second);
        byte[] data = this.bytes(output);
        ThriftWireCodec codec = this.codec();

        // 边界扫描和完整解码均接受 UUID 容器。
        assertEquals(data.length, codec.scan(data, 0, data.length));
        ThriftMessage message = codec.decode(data);
        assertEquals("binary", message.encoding());
        TProtocol reader = codec.createProtocol("binary", new TMemoryInputTransport(message.data()));
        assertEquals("uuidList", reader.readMessageBegin().name);
        reader.readStructBegin();
        assertEquals(TType.LIST, reader.readFieldBegin().type);
        TList list = reader.readListBegin();
        assertEquals(TType.UUID, list.elemType);
        assertEquals(2, list.size);
        assertEquals(first, reader.readUuid());
        assertEquals(second, reader.readUuid());
        reader.readListEnd();
        reader.readFieldEnd();
        assertEquals(TType.STOP, reader.readFieldBegin().type);
        reader.readStructEnd();
        reader.readMessageEnd();
    }

    @Test
    public void compactDecodesUuidListFromOfficialWriter() throws Exception {
        // UUID 元素的 Compact 类型码与定长内容由官方实现输出。
        UUID first = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        UUID second = UUID.fromString("fedcba98-7654-3210-ffff-0123456789ab");
        TMemoryBuffer output = new TMemoryBuffer(128);
        this.writeUuidList(new TCompactProtocol(output), first, second);
        byte[] data = this.bytes(output);
        ThriftWireCodec codec = this.codec();

        // UUID 元素参与最小长度检查后仍能按原值读取。
        assertEquals(data.length, codec.scan(data, 0, data.length));
        ThriftMessage message = codec.decode(data);
        assertEquals("compact", message.encoding());
        TProtocol reader = codec.createProtocol("compact", new TMemoryInputTransport(message.data()));
        assertEquals("uuidList", reader.readMessageBegin().name);
        reader.readStructBegin();
        assertEquals(TType.LIST, reader.readFieldBegin().type);
        TList list = reader.readListBegin();
        assertEquals(TType.UUID, list.elemType);
        assertEquals(2, list.size);
        assertEquals(first, reader.readUuid());
        assertEquals(second, reader.readUuid());
        reader.readListEnd();
        reader.readFieldEnd();
        assertEquals(TType.STOP, reader.readFieldBegin().type);
        reader.readStructEnd();
        reader.readMessageEnd();
    }

    @Test
    public void jsonDecodesUuidListFromOfficialWriter() throws Exception {
        // 官方 JSON 使用 uid 类型描述 UUID 容器元素。
        UUID first = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        UUID second = UUID.fromString("fedcba98-7654-3210-ffff-0123456789ab");
        TMemoryBuffer output = new TMemoryBuffer(128);
        this.writeUuidList(new TJSONProtocol(output), first, second);
        byte[] data = this.bytes(output);
        ThriftWireCodec codec = this.codec();

        // JSON 预校验和官方协议读取共同保留 UUID 原值。
        assertEquals(data.length, codec.scan(data, 0, data.length));
        ThriftMessage message = codec.decode(data);
        assertEquals("json", message.encoding());
        TProtocol reader = codec.createProtocol("json", new TMemoryInputTransport(message.data()));
        assertEquals("uuidList", reader.readMessageBegin().name);
        reader.readStructBegin();
        assertEquals(TType.LIST, reader.readFieldBegin().type);
        TList list = reader.readListBegin();
        assertEquals(TType.UUID, list.elemType);
        assertEquals(2, list.size);
        assertEquals(first, reader.readUuid());
        assertEquals(second, reader.readUuid());
        reader.readListEnd();
        reader.readFieldEnd();
        assertEquals(TType.STOP, reader.readFieldBegin().type);
        reader.readStructEnd();
        reader.readMessageEnd();
    }

    @Test
    public void binaryAcceptsExactConfiguredMessageLength() throws Exception {
        // 消息长度恰好等于收发两侧的上限。
        byte[] data = this.ping("binary", 41);
        ThriftWireCodec bounded = new ThriftWireCodec(data.length, 128, 32);

        // 完整消息在边界处可以扫描、解码和重新编码。
        assertEquals(data.length, bounded.scan(data, 0, data.length));
        assertEquals(41, bounded.decode(data).sequenceId());
        byte[] encoded = bounded.encode("binary", "ping", TMessageType.CALL, 41, new InteropService.ping_args());
        assertArrayEquals(data, encoded);
    }

    @Test
    public void compactAcceptsExactConfiguredMessageLength() throws Exception {
        // Compact 消息的实际编码长度作为精确边界。
        byte[] data = this.ping("compact", 42);
        ThriftWireCodec bounded = new ThriftWireCodec(data.length, 128, 32);

        // 最后一字节恰好位于上限时仍为合法完整消息。
        assertEquals(data.length, bounded.scan(data, 0, data.length));
        assertEquals(42, bounded.decode(data).sequenceId());
        byte[] encoded = bounded.encode("compact", "ping", TMessageType.CALL, 42, new InteropService.ping_args());
        assertArrayEquals(data, encoded);
    }

    @Test
    public void jsonAcceptsExactConfiguredMessageLength() throws Exception {
        // JSON 的消息数组右括号恰好落在上限处。
        byte[] data = this.ping("json", 43);
        ThriftWireCodec bounded = new ThriftWireCodec(data.length, 128, 32);

        // 精确边界不被当作未闭合或超限。
        assertEquals(data.length, bounded.scan(data, 0, data.length));
        assertEquals(43, bounded.decode(data).sequenceId());
        byte[] encoded = bounded.encode("json", "ping", TMessageType.CALL, 43, new InteropService.ping_args());
        assertArrayEquals(data, encoded);
    }

    private ThriftWireCodec codec() {
        return new ThriftWireCodec(1024 * 1024, 128, 32);
    }

    private byte[] ping(String encoding, int sequenceId) throws TException {
        return this.codec().encode(encoding, "ping", TMessageType.CALL, sequenceId, new InteropService.ping_args());
    }

    private void writePing(TProtocol protocol, int sequenceId) throws TException {
        protocol.writeMessageBegin(new TMessage("ping", TMessageType.CALL, sequenceId));
        new InteropService.ping_args().write(protocol);
        protocol.writeMessageEnd();
    }

    private void writeNestedStruct(TProtocol protocol, int levels) throws TException {
        // 仅生成具有指定嵌套深度的官方 wire，不模拟被测解码逻辑。
        protocol.writeStructBegin(new TStruct("nested"));
        if (levels > 1) {
            protocol.writeFieldBegin(new TField("child", TType.STRUCT, (short) 1));
            this.writeNestedStruct(protocol, levels - 1);
            protocol.writeFieldEnd();
        }
        protocol.writeFieldStop();
        protocol.writeStructEnd();
    }

    private void writeUuidList(TProtocol protocol, UUID first, UUID second) throws TException {
        // 使用官方写入 API 构造 UUID 容器正文。
        protocol.writeMessageBegin(new TMessage("uuidList", TMessageType.CALL, 1));
        protocol.writeStructBegin(new TStruct("args"));
        protocol.writeFieldBegin(new TField("values", TType.LIST, (short) 1));
        protocol.writeListBegin(new TList(TType.UUID, 2));
        protocol.writeUuid(first);
        protocol.writeUuid(second);
        protocol.writeListEnd();
        protocol.writeFieldEnd();
        protocol.writeFieldStop();
        protocol.writeStructEnd();
        protocol.writeMessageEnd();
    }

    private byte[] bytes(TMemoryBuffer output) {
        return Arrays.copyOf(output.getArray(), output.length());
    }

    private byte[] json(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] withTrailingByte(byte[] data) {
        byte[] result = Arrays.copyOf(data, data.length + 1);
        result[data.length] = 0x7f;
        return result;
    }

    private byte[] join(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
