/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetReturn;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.soap.SoapProtocol;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoapProtocolMetaTest {
    @Test
    public void schemaDescriptionKeepsTheChameleonIncludeNamespace() throws Exception {
        // 无 targetNamespace 的 XSD 由包含它的 WSDL 决定有效命名空间。
        SoapWsdlResource resource = new SoapWsdlResource(this.getClass().getResource("/wsdl/chameleon.wsdl"));
        ObjectNode schemas = resource.describeSchemas();

        // 组件名称和未加前缀的类型引用使用同一个已编译 Schema 视图。
        String namespace = "{urn:meteor:test:chameleon}";
        JsonNode element = schemas.path("elements").path(namespace + "notify");
        assertEquals(namespace + "NotifyRequest", element.path("attributes").path("type").asText());
        assertTrue(schemas.path("types").has(namespace + "NotifyRequest"));
    }

    @Test
    public void generatedDescriptionContainsActualInputAndOutputNames() {
        // 自动生成的 WSDL 和已编译 SOAP 映射共同导出端点描述。
        SoapProtocolMeta binding = this.prepare(GeneratedNames.class, null);
        ObjectNode description = binding.describeContract(JsonNodeFactory.instance.objectNode());
        JsonNode method = description.path("methods").get(0);

        // 方法、参数与返回名称保留公共注解在实际 XML 契约中的映射。
        assertEquals("Echo", method.path("name").asText());
        assertEquals("payload", method.path("parameters").get(0).path("name").asText());
        assertEquals("answer", method.path("returns").get(0).path("name").asText());
        assertFalse(description.path("schemas").path("elements").isEmpty());
    }

    @Test
    public void descriptionListsPublicNamesBeforeJavaAndSoapDetails() {
        // 同一份 SOAP 契约先呈现公共名称与 Java 定位，再呈现消息主体和协议扩展。
        SoapProtocolMeta binding = this.prepare(GeneratedNames.class, null);
        ObjectNode description = binding.describeContract(JsonNodeFactory.instance.objectNode());
        JsonNode method = description.path("methods").get(0);
        JsonNode parameter = method.path("parameters").get(0);
        JsonNode result = method.path("returns").get(0);

        // 名称与公共注解一致，XML 专属信息排在主体之后。
        String[] serviceFields = { "methods", "types", "schemas", "version", "namespace" };
        assertArrayEquals(serviceFields, fieldNames(description));
        String[] methodFields = { "name", "javaSignature", "message", "parameters", "returns", "faults", //
                "soapAction", "style", "use", "requestWrapper", "responseWrapper" };
        assertArrayEquals(methodFields, fieldNames(method));
        String[] parameterFields = { "name", "argIndex", "type", "element", "minOccurs", "maxOccurs" };
        assertArrayEquals(parameterFields, fieldNames(parameter));
        assertEquals("payload", parameter.path("name").asText());
        assertEquals(0, parameter.path("argIndex").asInt());
        assertArrayEquals(new String[] { "name", "type", "element", "minOccurs", "maxOccurs" }, fieldNames(result));
        assertEquals("answer", result.path("name").asText());
    }

    @Test
    public void importedDescriptionKeepsWireOrderSeparateFromJavaArgIndex() throws Exception {
        // 文件将 String 放在首位，Java 将 int 放在首位。
        SoapProtocolMeta binding = this.prepare(ReorderedNames.class, this.resource());
        ObjectNode description = binding.describeContract(JsonNodeFactory.instance.objectNode());
        JsonNode parameters = description.path("methods").get(0).path("parameters");

        // 上报真实 WSDL 顺序和对应的 Java 下标，不把数组顺序误报为 Java 位置。
        assertEquals("left", parameters.get(0).path("name").asText());
        assertEquals(1, parameters.get(0).path("argIndex").asInt());
        assertEquals("right", parameters.get(1).path("name").asText());
        assertEquals(0, parameters.get(1).path("argIndex").asInt());
        assertFalse(description.path("schemas").path("types").isMissingNode());
    }

    @Test
    public void commonMethodParameterAndReturnNamesGenerateTheContract() {
        // 不提供文件，公共注解命名操作与两条消息的元素。
        SoapProtocolMeta binding = this.prepare(GeneratedNames.class, null);
        SoapOperation operation = binding.resource().definition().operation("Echo");

        // Java 方法名独立保留，Dispatcher 不需要感知 WSDL 操作名称。
        assertEquals("echo", binding.method("Echo").getName());
        assertEquals("payload", operation.inputBody().parts().get(0).name());
        assertEquals("answer", operation.outputBody().parts().get(0).name());
    }

    @Test
    public void fileParameterNamesCanReorderDifferentJavaTypes() throws Exception {
        // WSDL 是 String、int，Java 声明顺序相反。
        SoapProtocolMeta binding = this.prepare(ReorderedNames.class, this.resource());
        Method method = ReorderedNames.class.getMethod("combine", int.class, String.class);

        // 类型和数值都使用同一个参数对应表。
        assertEquals(String.class, binding.wireParameterTypes(method)[0].getRawClass());
        assertArrayEquals(new Object[] { "text", 7 }, binding.wireParameters(method, new Object[] { 7, "text" }));
        assertArrayEquals(new Object[] { 7, "text" }, binding.javaParameters(method, new Object[] { "text", 7 }));
    }

    @Test
    public void explicitParameterNameCannotReplaceAnExistingFilePart() throws Exception {
        // 文件没有 missing 参数，不能按相似名称或 Java 顺序回退。
        SoapWsdlResource resource = this.resource();
        assertThrows(IllegalArgumentException.class, () -> this.prepare(WrongParameter.class, resource));
    }

    @Test
    public void explicitReturnNameMustMatchTheExistingFilePart() throws Exception {
        // 已有 WSDL 的输出为 result，不接受 Java 侧改成 missing。
        SoapWsdlResource resource = this.resource();
        assertThrows(IllegalArgumentException.class, () -> this.prepare(WrongReturn.class, resource));
    }

    @Test
    public void namedResourceDoesNotFallBackToGeneratedWsdlWhenItsTypeIsWrong() {
        // 资源明确属于 SOAP，错误类型不能当作其他协议的共享声明忽略。
        assertThrows(IllegalArgumentException.class, () -> this.prepare(GeneratedNames.class, new Object()));
    }

    private <T> SoapProtocolMeta prepare(Class<T> type, Object resource) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setServiceType(MetServiceType.Provider);
        service.putAliasName("soap", "Names");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of());
        ProtocolBinding result = new SoapProtocol().prepareBinding(config, null, meta);
        assertNotNull(result);
        return (SoapProtocolMeta) result.getDefinition();
    }

    private static String[] fieldNames(JsonNode description) {
        List<String> fields = new ArrayList<>();
        description.fieldNames().forEachRemaining(fields::add);
        return fields.toArray(String[]::new);
    }

    private SoapWsdlResource resource() throws Exception {
        return new SoapWsdlResource(this.getClass().getResource("/mapping/names.wsdl"));
    }

    interface GeneratedNames {
        @MetMethod(name = "Echo")
        @MetReturn(name = "answer")
        String echo(@MetParam(name = "payload") String input);
    }

    interface ReorderedNames {
        @MetMethod(name = "Join")
        @MetReturn(name = "result")
        String combine(@MetParam(name = "right") int number, @MetParam(name = "left") String text);
    }

    interface WrongParameter {
        @MetMethod(name = "Join")
        String combine(@MetParam(name = "missing") int number, @MetParam(name = "left") String text);
    }

    interface WrongReturn {
        @MetMethod(name = "Join")
        @MetReturn(name = "missing")
        String combine(@MetParam(name = "right") int number, @MetParam(name = "left") String text);
    }
}
