/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetType;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.oncrpc.annotation.OncRpcProgram;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.codec.XdrInput;
import net.hasor.meteor.protocol.oncrpc.codec.XdrOutput;
import org.junit.Test;
import static org.junit.Assert.*;

/** RPC/XDR 文件的静态绑定及值编解码，和网络生命周期分开验证。 */
public class OncRpcIdlBindingTest {
    @Test
    public void descriptionPreservesFileProcedureIdentityAndJavaParameterOrder() throws Exception {
        // 同一份已编译定义同时供运行期 XDR 编解码和注册描述使用。
        ObjectNode description = this.binding().describeContract(JsonNodeFactory.instance.objectNode());
        JsonNode selected = null;
        for (var method : description.path("methods")) {
            if (StringUtils.equals("subtract", method.path("name").asText())) {
                selected = method;
            }
        }

        // 文件顺序为 left、right，Java 调用顺序为 right、left。
        assertNotNull(selected);
        assertEquals(1, selected.path("argIndexes").get(0).asInt());
        assertEquals(0, selected.path("argIndexes").get(1).asInt());
        assertFalse(description.path("types").isEmpty());
    }

    @Test
    public void fileSuppliesProgramVersionAndProcedureWithoutAnnotations() throws Exception {
        // Java 接口不标程序或过程号，全部身份从文件取得。
        OncRpcServiceBinding binding = this.binding();

        assertEquals(0x20001111L, binding.getProgram());
        assertEquals(1, binding.getVersion());
        assertEquals(1, binding.getProcedure("mirror"));
        assertEquals("mirror", binding.getMethods().get(1L).getName());
    }

    @Test
    public void namedJavaParametersCanHaveADifferentOrderFromTheXdrStruct() throws Exception {
        // Java 声明 right、left，文件正文的顺序是 left、right。
        OncRpcServiceBinding binding = this.binding();
        Object[] arguments = { 4, 19 };
        XdrOutput output = new XdrOutput(1024, 100, 16);

        for (int wireIndex = 0; wireIndex < arguments.length; wireIndex++) {
            int javaIndex = binding.parameterIndex("difference", wireIndex);
            binding.parameter("difference", wireIndex).write(output, arguments[javaIndex]);
        }

        assertArrayEquals(new byte[] { 0, 0, 0, 19, 0, 0, 0, 4 }, output.toByteArray());
    }

    @Test
    public void fileOrderOverridesTheSameDtosAutomaticFieldNumberOrder() throws Exception {
        // 自动定义按公共编号先写 y；已有文件要求改名后的 left 先于 right。
        XdrCodec<Object> automatic = new XdrSchemaBuilder(null, 1024, 100) //
                .codec(ResolvableType.forClass(Point.class));
        XdrCodec<Object> imported = this.binding().parameter("mirror", 0);
        Point value = new Point();
        value.x = 7;
        value.y = 19;
        XdrOutput generatedBody = new XdrOutput(1024, 100, 16);
        XdrOutput importedBody = new XdrOutput(1024, 100, 16);

        // 相同 DTO 的两份契约各自决定正文顺序，文件不会被公共字段号重排。
        automatic.write(generatedBody, value);
        imported.write(importedBody, value);
        assertArrayEquals(new byte[] { 0, 0, 0, 19, 0, 0, 0, 7 }, generatedBody.toByteArray());
        assertArrayEquals(new byte[] { 0, 0, 0, 7, 0, 0, 0, 19 }, importedBody.toByteArray());
        Point restored = (Point) imported.read(new XdrInput(importedBody.toByteArray(), 1024, 100, 16));
        assertEquals(7, restored.x);
        assertEquals(19, restored.y);
    }

    @Test
    public void fileMembersMatchPublicNamesWithoutRequiringPositiveFieldIds() throws Exception {
        // 文件按 left、right 映射 Java 的 first、second，公共编号可以缺失或为负。
        ProtocolConfig protocol = ProtocolConfig.newConfig("oncrpc", null, Map.of());
        ServiceDomain<FileNamedPoints> service = new ServiceDomain<>(FileNamedPoints.class);
        OncRpcServiceBinding binding = new OncRpcServiceBinding(this.resource(), service, protocol);
        XdrCodec<Object> codec = binding.parameter("mirror", 0);
        FileNamedPoint value = new FileNamedPoint();
        value.first = 11;
        value.second = 13;
        XdrOutput output = new XdrOutput(1024, 100, 16);

        // 实际文件类型与命名仍必须匹配，字段号不参与这里的顺序判断。
        codec.write(output, value);
        FileNamedPoint restored = (FileNamedPoint) codec.read(new XdrInput(output.toByteArray(), 1024, 100, 16));
        assertArrayEquals(new byte[] { 0, 0, 0, 11, 0, 0, 0, 13 }, output.toByteArray());
        assertEquals(11, restored.first);
        assertEquals(13, restored.second);
    }

    @Test
    public void unsignedIntRetainsValuesAboveTheSignedIntRange() throws Exception {
        // 文件明确 unsigned int，Java 使用 long 承载完整值域。
        XdrCodec<Object> codec = this.binding().parameter("unsignedEcho", 0);
        XdrOutput output = new XdrOutput(1024, 100, 16);
        codec.write(output, 0xffffffffL);

        Object result = codec.read(new XdrInput(output.toByteArray(), 1024, 100, 16));

        assertEquals(0xffffffffL, result);
    }

    @Test
    public void fixedVariableAndOptionalMembersFollowTheFile() throws Exception {
        // 固定 opaque、固定数组、变长数组与空指针由 IDL 分别决定。
        XdrCodec<Object> codec = this.binding().parameter("details", 0);
        Holder input = this.holder();
        input.note = null;
        input.value.kind = Mode.NONE;
        XdrOutput output = new XdrOutput(1024, 100, 16);
        codec.write(output, input);

        Holder result = (Holder) codec.read(new XdrInput(output.toByteArray(), 1024, 100, 16));

        assertEquals(0xffffffffL, result.count);
        assertArrayEquals(input.digest, result.digest);
        assertNull(result.note);
        assertArrayEquals(input.fixed, result.fixed);
        assertArrayEquals(input.variable, result.variable);
    }

    @Test
    public void unionUsesTheDeclaredEnumValueAndOnlyItsActiveArm() throws Exception {
        // Java enum 无编号注解，文件指定 TEXT=7，而不是 Java ordinal 1。
        XdrCodec<Object> codec = this.binding().parameter("details", 0);
        Holder input = this.holder();
        input.value.kind = Mode.TEXT;
        input.value.text = "hello";
        XdrOutput output = new XdrOutput(1024, 100, 16);
        codec.write(output, input);

        Holder result = (Holder) codec.read(new XdrInput(output.toByteArray(), 1024, 100, 16));

        assertSame(Mode.TEXT, result.value.kind);
        assertEquals("hello", result.value.text);
        assertNull(result.value.data);
    }

    @Test
    public void fixedArrayLengthMismatchIsRejected() throws Exception {
        // 文件要求恰好两个 int，不增加数组长度前缀掩盖不匹配。
        XdrCodec<Object> codec = this.binding().parameter("details", 0);
        Holder input = this.holder();
        input.fixed = new int[] { 1 };
        XdrOutput output = new XdrOutput(1024, 100, 16);

        assertThrows(IllegalArgumentException.class, () -> codec.write(output, input));
    }

    @Test
    public void mismatchedProgramAnnotationIsRejected() throws Exception {
        // 显式程序号不能被文件中的另一个合法程序悄悄覆盖。
        OncRpcIdlResource resource = this.resource();
        ProtocolConfig config = ProtocolConfig.newConfig("oncrpc", null, Map.of());
        ServiceDomain<WrongProgram> service = new ServiceDomain<>(WrongProgram.class);

        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceBinding(resource, service, config));
    }

    @Test
    public void selectedVersionMustExistInTheFile() throws Exception {
        // 选择器只选已有版本，不创建文件未声明的新版本。
        OncRpcIdlResource resource = this.resource().selectProgram("Example", 2);
        ProtocolConfig config = ProtocolConfig.newConfig("oncrpc", null, Map.of());
        ServiceDomain<Functions> service = new ServiceDomain<>(Functions.class);

        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceBinding(resource, service, config));
    }

    @Test
    public void sourceExportPreservesTheBoundFile() throws Exception {
        // 资源导出提供原文，不把已选 Java 方法子集反写到文件。
        OncRpcIdlResource resource = this.resource();

        String source = resource.getSources().get("service.x");

        assertTrue(source.contains("union Choice switch"));
        assertTrue(source.contains("unsigned int unsignedEcho(unsigned int) = 3"));
    }

    private OncRpcServiceBinding binding() throws Exception {
        ProtocolConfig config = ProtocolConfig.newConfig("oncrpc", null, Map.of());
        return new OncRpcServiceBinding(this.resource(), new ServiceDomain<>(Functions.class), config);
    }

    private OncRpcIdlResource resource() throws Exception {
        return new OncRpcIdlResource(this.getClass().getResource("/xdr/service.x"));
    }

    private Holder holder() {
        Holder value = new Holder();
        value.count = 0xffffffffL;
        value.digest = new byte[] { 1, 2, 3, 4 };
        value.note = "note";
        value.value = new Choice();
        value.value.kind = Mode.NONE;
        value.fixed = new int[] { 5, 6 };
        value.variable = new int[] { 7, 8, 9 };
        return value;
    }

    public interface Functions {
        @MetMethod(name = "echo")
        Point mirror(Point input);

        @MetMethod(name = "subtract")
        int difference(@MetParam(name = "right") int right, @MetParam(name = "left") int left);

        long unsignedEcho(long value);

        Holder details(Holder value);
    }

    @OncRpcProgram(id = 0x20009999)
    public interface WrongProgram extends Functions {
    }

    public static class Point {
        @MetField(name = "left", id = 90)
        private int x;
        @MetField(name = "right", id = 7)
        private int y;
    }

    public interface FileNamedPoints {
        @MetMethod(name = "echo")
        FileNamedPoint mirror(FileNamedPoint input);
    }

    public static class FileNamedPoint {
        @MetField(name = "right", id = -5)
        private int second;
        @MetField(name = "left")
        private int first;
    }

    public enum Mode {
        NONE,
        TEXT,
        DATA
    }

    public static class Choice {
        private Mode   kind;
        private String text;
        private byte[] data;
    }

    @MetType(name = "Holder")
    public static class Holder {
        private long   count;
        private byte[] digest;
        private String note;
        private Choice value;
        private int[]  fixed;
        private int[]  variable;
    }
}
