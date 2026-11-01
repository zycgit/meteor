/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.annotation.*;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.codec.XdrInput;
import net.hasor.meteor.protocol.oncrpc.codec.XdrOutput;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class XdrContractWriterTest {
    @Test
    public void generatedIntFieldsRebindToTheirNarrowJavaTypes() throws Exception {
        // 导出契约统一使用标准 int，Java 字段仍保留 Byte、Short 和 Character。
        ServiceDomain<NarrowNumbers> service = new ServiceDomain<>(NarrowNumbers.class);
        ProtocolConfig config = ProtocolConfig.newConfig("oncrpc", "oncrpc", Map.of());
        OncRpcServiceResource declaration = new OncRpcServiceResource(NarrowNumbers.class);
        OncRpcServiceBinding original = new OncRpcServiceBinding(declaration, service, config);
        Path directory = Path.of("build", "contracts", "narrow-numbers");
        Files.createDirectories(directory);
        Path file = directory.resolve("NarrowNumbers.x");
        Files.writeString(file, original.getSources().get("NarrowNumbers.x"));

        // 用原 Java DTO 重绑导出的文件，读取独立黄金报文，不能仅检查导出文本。
        OncRpcIdlResource resource = new OncRpcIdlResource(file.toUri().toURL());
        OncRpcServiceBinding restored = new OncRpcServiceBinding(resource, service, config);
        byte[] body = { -1, -1, -1, (byte) 0x80, 0, 0, 0x7f, -1, 0, 0, 0x4e, 0x2d };
        NarrowValues values = (NarrowValues) restored.result("mirror").read(new XdrInput(body, 1024, 100, 16));
        assertEquals(Byte.valueOf(Byte.MIN_VALUE), values.small);
        assertEquals(Short.valueOf(Short.MAX_VALUE), values.medium);
        assertEquals(Character.valueOf('中'), values.character);

        // 重新写入仍是相同的三个 int32，没有隐式 optional 判别值。
        XdrOutput output = new XdrOutput(1024, 100, 16);
        restored.parameter("mirror", 0).write(output, values);
        assertArrayEquals(body, output.toByteArray());
    }

    @Test
    public void genericDtoInstancesKeepDistinctNamesWhenRebindingTheExportedFile() throws Exception {
        // 两个泛型实参进入独立的 XDR 类型，成员顺序由公共字段编号排序确定。
        ServiceDomain<GenericNotes> service = new ServiceDomain<>(GenericNotes.class);
        ProtocolConfig config = ProtocolConfig.newConfig("oncrpc", "oncrpc", Map.of());
        OncRpcServiceResource declaration = new OncRpcServiceResource(GenericNotes.class);
        OncRpcServiceBinding original = new OncRpcServiceBinding(declaration, service, config);
        Path directory = Path.of("build", "contracts", "generics");
        Files.createDirectories(directory);
        Path file = directory.resolve("GenericNotes.x");
        Files.writeString(file, original.getSources().get("GenericNotes.x"));

        // 导出的两个 Box 类型都能与相同 Java 泛型属性绑定，不发生同名覆盖。
        OncRpcIdlResource resource = new OncRpcIdlResource(file.toUri().toURL());
        OncRpcServiceBinding restored = new OncRpcServiceBinding(resource, service, config);
        Pair value = new Pair();
        value.text = new Box<>();
        value.text.value = "generic";
        value.number = new Box<>();
        value.number.value = 7;
        XdrOutput output = new XdrOutput(1024, 100, 16);
        restored.parameter("mirror", 0).write(output, value);
        Pair result = (Pair) restored.result("mirror").read(new XdrInput(output.toByteArray(), 1024, 100, 16));
        assertEquals("generic", result.text.value);
        assertEquals(Integer.valueOf(7), result.number.value);
    }

    @Test
    public void annotationDefinitionExportsAnEquivalentRpcContract() throws Exception {
        // 多 Java 参数成为参数 struct，DTO 顺序、枚举编号和容器边界来自已编译图。
        ServiceDomain<Notes> service = new ServiceDomain<>(Notes.class);
        ProtocolConfig protocol = ProtocolConfig.newConfig("oncrpc", "oncrpc", Map.of());
        OncRpcServiceResource declaration = new OncRpcServiceResource(Notes.class);
        OncRpcServiceBinding original = new OncRpcServiceBinding(declaration, service, protocol);
        Map<String, String> sources = original.getSources();
        Path directory = Path.of("build", "contracts", "annotations");
        Files.createDirectories(directory);
        Path file = directory.resolve("Notes.x");
        Files.writeString(file, sources.get("Notes.x"));

        // 文件重新加载到同一运行期绑定，比较真实 XDR 正文而非仅比较描述文本。
        OncRpcIdlResource resource = new OncRpcIdlResource(file.toUri().toURL());
        OncRpcServiceBinding restored = new OncRpcServiceBinding(resource, service, protocol);
        Note input = new Note();
        input.mode = Mode.READ;
        input.text = "export";
        input.values = List.of(2, 7);
        XdrOutput first = new XdrOutput(1024, 100, 16);
        XdrOutput second = new XdrOutput(1024, 100, 16);
        original.parameter("mirror", 0).write(first, input);
        restored.parameter("mirror", 0).write(second, input);
        assertArrayEquals(first.toByteArray(), second.toByteArray());
        Note decoded = (Note) restored.result("mirror").read(new XdrInput(first.toByteArray(), 1024, 100, 16));
        assertSame(Mode.READ, decoded.mode);
        assertEquals(input.values, decoded.values);
        assertEquals(7, restored.getProcedure("add"));
    }

    @Test
    public void exportedStructMembersFollowAscendingCommonFieldIds() {
        // Java 先声明 values，公共编号要求契约按 mode、text、values 导出。
        ServiceDomain<Notes> service = new ServiceDomain<>(Notes.class);
        ProtocolConfig protocol = ProtocolConfig.newConfig("oncrpc", "oncrpc", Map.of());
        OncRpcServiceResource resource = new OncRpcServiceResource(Notes.class);
        OncRpcServiceBinding binding = new OncRpcServiceBinding(resource, service, protocol);

        // 生成文件沿用已编译类型图，编号间隙不会产生额外成员。
        String source = binding.getSources().get("Notes.x");
        int mode = source.indexOf(" mode;");
        int text = source.indexOf(" text<");
        int values = source.indexOf(" values<");
        assertTrue(mode >= 0);
        assertTrue(text > mode);
        assertTrue(values > text);
    }

    @Test
    public void arbitraryCustomCodecCannotBeInventedAsAnIdlType() {
        // 自定义 Codec 可照常使用，但不能从它的 Java 类或一次编码猜测类型定义。
        OncRpcServiceResource resource = new OncRpcServiceResource(Notes.class);
        resource.codec(Note.class, new XdrCodec<>() {
            @Override
            public Note read(XdrInput input) {
                input.readInt();
                return new Note();
            }

            @Override
            public void write(XdrOutput output, Note value) {
                output.writeInt(0);
            }
        });
        ProtocolConfig protocol = ProtocolConfig.newConfig("oncrpc", "oncrpc", Map.of());
        OncRpcServiceBinding binding = new OncRpcServiceBinding(resource, new ServiceDomain<>(Notes.class), protocol);

        assertThrows(UnsupportedOperationException.class, binding::getSources);
    }

    @MetService(id = 0x20002222)
    public interface Notes {
        @MetMethod(name = "Echo", id = 1)
        Note mirror(Note input);

        @MetMethod(id = 7)
        long add(@MetParam(name = "left") int first, @MetParam(name = "right") long second);
    }

    @MetService(id = 0x20002224)
    public interface NarrowNumbers {
        @MetMethod(id = 1)
        NarrowValues mirror(NarrowValues value);
    }

    public static class NarrowValues {
        @MetField(id = 1)
        public Byte      small;
        @MetField(id = 2)
        public Short     medium;
        @MetField(id = 3)
        public Character character;
    }

    public static class Note {
        @MetField(id = 30)
        public List<Integer> values;
        @MetField(id = 12)
        public String        text;
        @MetField(id = 3)
        public Mode          mode;
    }

    public enum Mode {
        @MetEnumValue(name = "READ_MODE", id = 7) READ,
        @MetEnumValue(name = "NO_MODE", id = 0) NONE
    }

    @MetService(id = 0x20002223)
    public interface GenericNotes {
        @MetMethod(id = 1)
        Pair mirror(Pair value);
    }

    public static class Pair {
        @MetField(id = 3)
        public Box<String>  text;
        @MetField(id = 12)
        public Box<Integer> number;
    }

    @MetType(name = "Box")
    public static class Box<T> {
        @MetField(id = 3)
        public T value;
    }

}
