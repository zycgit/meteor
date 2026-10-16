/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.codec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.HessianOutput;
import net.hasor.meteor.protocol.hessian.codec.HessianCodecTestFixture.BusinessException;
import net.hasor.meteor.protocol.hessian.codec.HessianCodecTestFixture.Person;
import org.junit.Test;
import static org.junit.Assert.assertThrows;

public class HessianWireGuardTest {
    private static final int MAX_MESSAGE_SIZE = 1024 * 1024;

    @Test
    public void hessianOneCallIsAccepted() throws Exception {
        // 官方编码器生成带方法名、位置参数和结束标记的完整调用。
        byte[] body = HessianCodecTestFixture.call(1, "echo", "你好");

        // 预检不反序列化业务对象，仅确认结构可被安全读取。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianOneCallRequestingVersionTwoReplyIsAccepted() throws Exception {
        // 老调用格式允许声明服务端使用 Hessian 2 返回。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HessianOutput output = new HessianOutput(bytes);
        output.setVersion(2);
        output.call("echo", new Object[] { "version-two-reply" });
        output.flush();

        // c 2 0 的调用体仍按 Hessian 1 读取。
        HessianWireGuard.validate(bytes.toByteArray(), MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianTwoCallIsAccepted() throws Exception {
        // Hessian 2 调用包含版本头、方法名与显式参数个数。
        byte[] body = HessianCodecTestFixture.call(2, "subtract", 9, 4);

        // 参数读取后无需 Hessian 1 的结束标记。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianOneReplyIsAccepted() throws Exception {
        // 完整 Hessian 1 响应包含 r 版本头与 z 结束标记。
        byte[] body = HessianCodecTestFixture.reply(1, "result");

        // 只接受一个返回值。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianTwoReplyIsAccepted() throws Exception {
        // 完整 Hessian 2 响应包含 H 版本头和 R 标记。
        byte[] body = HessianCodecTestFixture.reply(2, "result");

        // 官方编码器写出的响应无需额外结束标记。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianOneFaultIsAccepted() throws Exception {
        // fault 自身写入完整响应头，不能额外套 startReply。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HessianOutput output = new HessianOutput(bytes);
        output.writeFault("ServiceException", "业务失败", null);
        output.flush();

        // fault 键值集合及响应结束标记都参与结构校验。
        HessianWireGuard.validate(bytes.toByteArray(), MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianTwoFaultIsAccepted() throws Exception {
        // Hessian 2 的 fault 使用 F 和无类型 map。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.writeFault("ServiceException", "业务失败", null);
        output.flush();

        // fault 不应被当成普通对象引用或返回值。
        HessianWireGuard.validate(bytes.toByteArray(), MAX_MESSAGE_SIZE);
    }

    @Test
    public void declaredExceptionFaultDetailIsAccepted() throws Exception {
        // 声明异常携带自己的字段，官方编码器写入类定义与 detail。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.writeFault("ServiceException", "denied", new BusinessException(403, "denied"));
        output.flush();

        // 预检允许有类型的异常详情，不需要加载其 Java 类。
        HessianWireGuard.validate(bytes.toByteArray(), MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactSingleByteIntegerIsAccepted() throws Exception {
        // 官方编码器选择单字节紧凑整数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 23);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactTwoByteIntegerIsAccepted() throws Exception {
        // 官方编码器选择双字节紧凑整数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 1024);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactThreeByteIntegerIsAccepted() throws Exception {
        // 官方编码器选择三字节紧凑整数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 100_000);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void fullWidthIntegerIsAccepted() throws Exception {
        // 官方编码器选择完整四字节整数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, Integer.MAX_VALUE);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactSingleByteLongIsAccepted() throws Exception {
        // 官方编码器选择单字节紧凑长整数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 7L);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactTwoByteLongIsAccepted() throws Exception {
        // 官方编码器选择双字节紧凑长整数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 1024L);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactThreeByteLongIsAccepted() throws Exception {
        // 官方编码器选择三字节紧凑长整数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 100_000L);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactFourByteLongIsAccepted() throws Exception {
        // 官方编码器选择四字节紧凑长整数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 1_000_000L);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void fullWidthLongIsAccepted() throws Exception {
        // 官方编码器选择完整八字节长整数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, Long.MAX_VALUE);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactZeroDoubleIsAccepted() throws Exception {
        // 官方编码器选择零值紧凑浮点数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 0.0d);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactOneDoubleIsAccepted() throws Exception {
        // 官方编码器选择一值紧凑浮点数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 1.0d);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactByteDoubleIsAccepted() throws Exception {
        // 官方编码器选择单字节紧凑浮点数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 19.0d);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactShortDoubleIsAccepted() throws Exception {
        // 官方编码器选择双字节紧凑浮点数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 1024.0d);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactMillDoubleIsAccepted() throws Exception {
        // 官方编码器选择千分位紧凑浮点数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, 1.125d);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void fullWidthDoubleIsAccepted() throws Exception {
        // 官方编码器选择完整八字节浮点数对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, Math.PI);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactMinuteDateIsAccepted() throws Exception {
        // 官方编码器选择整分钟日期对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, new Date(120_000L));

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void millisecondDateIsAccepted() throws Exception {
        // 官方编码器选择毫秒精度日期对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, new Date(123_456L));

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void nullReplyIsAccepted() throws Exception {
        // 官方编码器选择空返回值对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, null);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void booleanReplyIsAccepted() throws Exception {
        // 官方编码器选择布尔返回值对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, Boolean.TRUE);

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void chineseStringIsAccepted() throws Exception {
        // 官方编码器选择多字节中文字符串对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, "中文协议报文");

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void supplementaryUnicodeStringIsAccepted() throws Exception {
        // 官方编码器选择UTF-16 代理对字符串对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, "hello\uD83D\uDE80world");

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void compactBinaryIsAccepted() throws Exception {
        // 官方编码器选择紧凑二进制数据对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, new byte[] { 0, 1, (byte) 0xff });

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void typedArrayIsAccepted() throws Exception {
        // 官方编码器选择带元素类型的定长数组对应的线格式。
        byte[] body = HessianCodecTestFixture.reply(2, new int[] { 1, 2, 3 });

        // 预检必须准确跳过该值，且在响应末尾停止。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void chunkedBinaryIsAccepted() throws Exception {
        // 连续块和最后一块由官方 writer 产生，块间不能当成独立返回值。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.startReply();
        output.writeByteBufferStart();
        output.writeByteBufferPart(new byte[] { 1, 2 }, 0, 2);
        output.writeByteBufferEnd(new byte[] { 3, 4 }, 0, 2);
        output.completeReply();
        output.flush();

        // 多块合起来表示一个二进制返回值。
        HessianWireGuard.validate(bytes.toByteArray(), MAX_MESSAGE_SIZE);
    }

    @Test
    public void chunkedUnicodeStringIsAccepted() throws Exception {
        // 长字符串会跨越 Hessian 的字符串块，字符数与字节数不同。
        String text = "中文\uD83D\uDE80".repeat(20_000);
        byte[] body = HessianCodecTestFixture.reply(2, text);

        // 结构校验应跨块读取字符，不能按 UTF-8 字节数误算长度。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void typedMapIsAccepted() throws Exception {
        // 直接使用官方 API 写入带类型名称的 map。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.startReply();
        output.writeMapBegin("java.util.LinkedHashMap");
        output.writeString("name");
        output.writeString("Ada");
        output.writeMapEnd();
        output.completeReply();
        output.flush();

        // 类型声明与键值结束标记属于同一个返回值。
        HessianWireGuard.validate(bytes.toByteArray(), MAX_MESSAGE_SIZE);
    }

    @Test
    public void variableLengthListIsAccepted() throws Exception {
        // 变长列表使用结束标记，不携带固定元素个数。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.startReply();
        output.writeListBegin(-1, null);
        output.writeString("Ada");
        output.writeInt(7);
        output.writeListEnd();
        output.completeReply();
        output.flush();

        // 列表结束标记不能误当作 RPC 报文结束。
        HessianWireGuard.validate(bytes.toByteArray(), MAX_MESSAGE_SIZE);
    }

    @Test
    public void dtoClassDefinitionIsAccepted() throws Exception {
        // 普通 DTO 会产生类名、字段列表和实例字段值。
        Person person = new Person();
        person.setName("Ada");
        byte[] body = HessianCodecTestFixture.reply(2, person);

        // 类型描述只需校验线结构，不触发类加载。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void repeatedClassDefinitionReferenceIsAccepted() throws Exception {
        // 同类型的两个实例共享类定义，但有独立的对象引用编号。
        Person first = new Person();
        first.setName("Ada");
        Person second = new Person();
        second.setName("Bob");
        List<Person> people = new ArrayList<>();
        people.add(first);
        people.add(second);
        byte[] body = HessianCodecTestFixture.reply(2, people);

        // 第二个对象的类引用指向此前已读出的字段定义。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void cyclicObjectReferenceIsAccepted() throws Exception {
        // DTO 的字段引用自己，由官方 serializer 写出引用编号。
        Person person = new Person();
        person.setName("cycle");
        person.setNext(person);
        byte[] body = HessianCodecTestFixture.reply(2, person);

        // 引用不会展开成无限嵌套。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void cyclicListReferenceIsAccepted() throws Exception {
        // 集合可以直接包含自己，对应合法的已分配对象引用。
        List<Object> values = new ArrayList<>();
        values.add(values);
        byte[] body = HessianCodecTestFixture.reply(2, values);

        // 预检按引用表判断合法性，不展开集合内容。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void cyclicHessianOneMapIsAccepted() throws Exception {
        // Hessian 1 以 R 加四字节编号引用已经出现的 map。
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("self", values);
        byte[] body = HessianCodecTestFixture.reply(1, values);

        // 自引用已经在容器开始处登记，因此属于合法返回值。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void emptyBodyIsRejected() {
        // RPC 报文至少需要包含版本头与调用或响应结构。
        byte[] body = new byte[0];

        // 空内容不能进入官方反序列化器。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void bodyOverConfiguredLimitIsRejected() throws Exception {
        // 限制针对整个 RPC 正文，包含版本头和结构标记。
        byte[] body = HessianCodecTestFixture.reply(2, "limit");
        int limit = body.length - 1;

        // 超限在扫描正文前被拒绝。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, limit));
    }

    @Test
    public void bodyAtConfiguredLimitIsAccepted() throws Exception {
        // 正文恰好达到限制应保留可用空间，不额外扣除版本头。
        byte[] body = HessianCodecTestFixture.reply(2, "limit");

        // 长度上界允许取等号。
        HessianWireGuard.validate(body, body.length);
    }

    @Test
    public void rawSerializedValueIsRejected() throws Exception {
        // 独立序列化值没有 RPC 版本头，不能冒充 HTTP 上的 Hessian 调用。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.writeString("bare-value");
        output.flush();
        byte[] body = bytes.toByteArray();

        // RSF 内的 Hessian 序列化不属于这里接受的 RPC 信封。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void truncatedHessianOneReplyIsRejected() throws Exception {
        // 去掉响应结束标记，但保留完整返回值。
        byte[] complete = HessianCodecTestFixture.reply(1, "reply");
        byte[] body = Arrays.copyOf(complete, complete.length - 1);

        // 值完整并不代表整个 RPC 响应完整。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void multipleRpcEnvelopesAreRejected() throws Exception {
        // 一个 HTTP 正文内拼接两个独立响应。
        byte[] reply = HessianCodecTestFixture.reply(2, "result");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(reply);
        bytes.write(reply);
        byte[] body = bytes.toByteArray();

        // 预检不能在第一个响应后忽略余下正文。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void excessiveNestingIsRejected() throws Exception {
        // 报文很小，但连续容器嵌套超过 64 层。
        Object nested = "leaf";
        for (int depth = 0; depth < 70; depth++) {
            List<Object> parent = new ArrayList<>();
            parent.add(nested);
            nested = parent;
        }
        byte[] body = HessianCodecTestFixture.reply(2, nested);

        // 防止递归解析耗尽线程栈。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void enormousClassFieldDeclarationIsRejected() throws Exception {
        // 几十字节的报文声明二十亿字段，不实际提供字段名。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.startReply();
        output.writeObjectBegin("example.Payload");
        output.writeClassFieldLength(Integer.MAX_VALUE);
        output.flush();
        byte[] body = bytes.toByteArray();

        // 预检必须按正文剩余量拒绝，不按声明数量分配数组。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void enormousListLengthDeclarationIsRejected() throws Exception {
        // 固定列表声明巨量元素，却未携带元素正文。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.startReply();
        output.writeListBegin(Integer.MAX_VALUE, null);
        output.flush();
        byte[] body = bytes.toByteArray();

        // 使用字节边界快速失败，不能尝试分配声明长度的容器。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void unknownObjectReferenceIsRejected() {
        // Q 后面的紧凑整数 0 指向尚未登记的对象。
        byte[] body = hessianTwoReply('Q', 0x90);

        // 引用必须指向当前报文中已出现的对象。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void unknownClassReferenceIsRejected() {
        // 0x60 代表使用第一个类定义，而正文没有任何类定义。
        byte[] body = hessianTwoReply(0x60);

        // 对象字段数量必须来自有效类定义。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void invalidUtfContinuationIsRejected() {
        // 一个字符的字符串中，UTF-8 前导字节后跟 ASCII 而非续字节。
        byte[] body = hessianTwoReply(0x01, 0xc2, 'x');

        // 字节长度尚够，但编码结构非法。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void truncatedUtfCharacterIsRejected() {
        // 声明一个字符，却只提供三字节 UTF-8 字符的前导字节。
        byte[] body = hessianTwoReply(0x01, 0xe4);

        // 不得越过正文尾部读取缺失字节。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void unsupportedRemoteReferenceIsRejected() throws Exception {
        // Hessian 1 允许传输可调用远程对象，但 Meteor 不提供该能力。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HessianOutput output = new HessianOutput(bytes);
        output.startReply();
        output.writeRemote("example.Service", "http://localhost/object");
        output.completeReply();
        output.flush();
        byte[] body = bytes.toByteArray();

        // 在反序列化之前拒绝远程引用，避免创建远程代理。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void legacyHessianCallHeadersAreAccepted() throws Exception {
        // 无参 startCall 产生 c 0 1 头，后面可以先写扩展头再写方法名。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HessianOutput output = new HessianOutput(bytes);
        output.startCall();
        output.writeHeader("trace");
        output.writeString("trace-id");
        output.writeMethod("echo");
        output.writeString("value");
        output.completeCall();
        output.flush();

        // 兼容官方旧格式，同时完整校验扩展头和调用正文。
        HessianWireGuard.validate(bytes.toByteArray(), MAX_MESSAGE_SIZE);
    }

    @Test
    public void negativeFixedListLengthIsRejected() {
        // X 表示定长无类型列表，-1 不能被解释为变长列表。
        byte[] body = hessianTwoReply('X', 0x8f, 'Z');

        // 变长列表有自己的 W 标记，不能借负长度绕过校验。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void consecutiveClassDefinitionsExceedingDepthAreRejected() throws Exception {
        // 官方读取器递归处理值前的连续类定义，也必须纳入深度限制。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.startReply();
        for (int definition = 0; definition < 70; definition++) {
            output.writeObjectBegin("example.Type" + definition);
            output.writeClassFieldLength(0);
        }
        output.writeNull();
        output.flush();
        byte[] body = bytes.toByteArray();

        // 即使没有嵌套集合或对象字段，也不能无限递归读取类定义。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    @Test
    public void hessianOneIntegerIsAccepted() throws Exception {
        // Hessian 1 的四字节整数使用独立于 Hessian 2 的编码规则。
        byte[] body = HessianCodecTestFixture.reply(1, Integer.MAX_VALUE);

        // 读取值后仍需完整消费响应结束标记。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianOneLongIsAccepted() throws Exception {
        // Hessian 1 的八字节长整数使用独立于 Hessian 2 的编码规则。
        byte[] body = HessianCodecTestFixture.reply(1, Long.MAX_VALUE);

        // 读取值后仍需完整消费响应结束标记。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianOneDoubleIsAccepted() throws Exception {
        // Hessian 1 的双精度浮点数使用独立于 Hessian 2 的编码规则。
        byte[] body = HessianCodecTestFixture.reply(1, Math.PI);

        // 读取值后仍需完整消费响应结束标记。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianOneDateIsAccepted() throws Exception {
        // Hessian 1 的毫秒日期使用独立于 Hessian 2 的编码规则。
        byte[] body = HessianCodecTestFixture.reply(1, new Date(123_456L));

        // 读取值后仍需完整消费响应结束标记。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianOneBinaryChunksAreAccepted() throws Exception {
        // Hessian 1 的跨块二进制使用独立于 Hessian 2 的编码规则。
        byte[] body = HessianCodecTestFixture.reply(1, new byte[70_000]);

        // 读取值后仍需完整消费响应结束标记。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianOneUnicodeChunksAreAccepted() throws Exception {
        // Hessian 1 的跨块 Unicode 字符串使用独立于 Hessian 2 的编码规则。
        byte[] body = HessianCodecTestFixture.reply(1, "中文\uD83D\uDE80".repeat(20_000));

        // 读取值后仍需完整消费响应结束标记。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void hessianOneTypedListIsAccepted() throws Exception {
        // Hessian 1 的带类型列表使用独立于 Hessian 2 的编码规则。
        byte[] body = HessianCodecTestFixture.reply(1, new int[] { 1, 2, 3 });

        // 读取值后仍需完整消费响应结束标记。
        HessianWireGuard.validate(body, MAX_MESSAGE_SIZE);
    }

    @Test
    public void classDefinitionsAndNestedFieldsShareDepthLimit() throws Exception {
        // 每层只增加一个类定义和一个对象，单独计算任一部分都没有超过 64 层。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.startReply();
        for (int depth = 0; depth < 40; depth++) {
            String type = "example.Child" + depth;
            output.writeObjectBegin(type);
            output.writeClassFieldLength(1);
            output.writeString("child");
            output.writeObjectBegin(type);
        }
        output.writeNull();
        output.completeReply();
        output.flush();
        byte[] body = bytes.toByteArray();

        // 官方解析同时递归类定义与字段，预检必须累计这两部分的深度。
        assertThrows(IOException.class, () -> HessianWireGuard.validate(body, MAX_MESSAGE_SIZE));
    }

    private static byte[] hessianTwoReply(int... valueBytes) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write('H');
        bytes.write(2);
        bytes.write(0);
        bytes.write('R');
        for (int value : valueBytes) {
            bytes.write(value);
        }
        return bytes.toByteArray();
    }
}
