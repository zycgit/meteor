/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.Assert.*;

public class SoapEncodedSchemaTest {
    private static final String XSD  = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final String WSDL = "http://schemas.xmlsoap.org/wsdl/";

    @Test
    public void soap11ArrayDeclaresItsEncodingBaseAndItemType() throws Exception {
        // 一维数组的抽象类型应来自 SOAP Encoding，而不是 literal 的普通 item 包装。
        byte[] wsdl = this.generated(ArrayService.class, "1.1", "rpc");
        Document document = this.document(wsdl);

        // 命名类型保留精确的成员类型，外部客户端可据此生成数组参数。
        Element restriction = (Element) document.getElementsByTagNameNS(XSD, "restriction").item(0);
        assertEquals("soapenc:Array", restriction.getAttribute("base"));
        Element attribute = (Element) restriction.getElementsByTagNameNS(XSD, "attribute").item(0);
        assertEquals("soapenc:arrayType", attribute.getAttribute("ref"));
        assertEquals("xsd:string[]", attribute.getAttributeNS(WSDL, "arrayType"));
    }

    @Test
    public void soap12ArrayUsesStandardAttributesWithoutAnUndefinedArrayBase() throws Exception {
        // SOAP 1.2 Encoding 的官方 Schema 没有 Array 命名类型。
        Document document = this.document(this.generated(ArrayService.class, "1.2", "rpc"));

        // 使用本地数组类型及标准属性组，不生成无效的 enc:Array 基类引用。
        assertEquals(0, document.getElementsByTagNameNS(XSD, "restriction").getLength());
        NodeList groups = document.getElementsByTagNameNS(XSD, "attributeGroup");
        assertEquals(2, groups.getLength());
        assertEquals("soapenc:arrayAttributes", ((Element) groups.item(0)).getAttribute("ref"));
        assertEquals("soapenc:commonAttributes", ((Element) groups.item(1)).getAttribute("ref"));
    }

    @Test
    public void soap11MultidimensionalArrayDeclaresItsRank() throws Exception {
        // Java 二维数组按 SOAP 多维数组声明，最内层元素为 int。
        Document document = this.document(this.generated(MatrixService.class, "1.1", "rpc"));

        // rank 必须出现在 arrayType 元数据中，不能被降为一维数组。
        Element attribute = (Element) document.getElementsByTagNameNS(XSD, "attribute").item(0);
        assertEquals("xsd:int[,]", attribute.getAttributeNS(WSDL, "arrayType"));
    }

    @Test
    public void soap11GeneratedArrayContractReloadsWithoutNetworkSchemas() throws Exception {
        // 只把生成的 WSDL 保存为本地文件；标准编码 Schema 由插件离线提供。
        Path wsdl = Files.createTempFile("meteor-encoded-array11", ".wsdl");
        try {
            Files.write(wsdl, this.generated(ArrayService.class, "1.1", "rpc"));
            SoapWsdlResource loaded = new SoapWsdlResource(wsdl.toUri().toURL());

            // 重新解析仍保留一个数组参数和 RPC/encoded 绑定。
            assertEquals("1.1", loaded.definition().version().displayName());
            SoapOperation operation = loaded.definition().operation("echo");
            SoapBody inputBody = operation.inputBody();
            assertEquals("encoded", inputBody.use());
            List<QName> inputTypes = inputBody.parts().stream() //
                    .map(SoapPart::type) //
                    .toList();
            assertEquals(1, inputTypes.size());
            assertEquals("Type1", inputTypes.get(0).getLocalPart());
        } finally {
            Files.deleteIfExists(wsdl);
        }
    }

    @Test
    public void soap12NestedCollectionContractReloadsWithoutNetworkSchemas() throws Exception {
        // 编码规则为每一层集合保留数组边界，允许 List<List<String>>。
        Path wsdl = Files.createTempFile("meteor-encoded-nested12", ".wsdl");
        try {
            Files.write(wsdl, this.generated(NestedCollectionService.class, "1.2", "rpc"));
            SoapWsdlResource loaded = new SoapWsdlResource(wsdl.toUri().toURL());

            // 嵌套集合在 Java 方法层仍是一个参数，不展开成多个 operation part。
            assertEquals("1.2", loaded.definition().version().displayName());
            SoapOperation operation = loaded.definition().operation("echo");
            assertEquals("encoded", operation.inputBody().use());
            String[] parameterElements = operation.parameterElements().stream() //
                    .map(Object::toString) //
                    .toArray(String[]::new);
            assertArrayEquals(new String[] { "arg0" }, parameterElements);
        } finally {
            Files.deleteIfExists(wsdl);
        }
    }

    @Test
    public void encodedBeanCollectionUsesAnArrayAccessor() throws Exception {
        // POJO 的集合属性在 encoded 中对应一个数组 accessor。
        Document document = this.document(this.generated(BeanService.class, "1.1", "rpc"));
        Element complex = (Element) document.getElementsByTagNameNS(XSD, "complexType").item(0);
        Element property = (Element) complex.getElementsByTagNameNS(XSD, "element").item(0);

        // 属性引用数组类型，而不是用重复的同名字段代替一个数组对象。
        assertEquals("values", property.getAttribute("name"));
        assertEquals("tns:Type2", property.getAttribute("type"));
        assertFalse(property.hasAttribute("maxOccurs"));
    }

    @Test
    public void generatedSoap11DocumentEncodedContractUsesBareTypeParts() throws Exception {
        // document/encoded 不额外创建 RPC wrapper，默认采用 bare 映射。
        byte[] definition = this.generated(ArrayService.class, "1.1", "document");
        Document document = this.document(definition);
        Path wsdl = Files.createTempFile("meteor-document-encoded11", ".wsdl");
        try {
            Files.write(wsdl, definition);
            SoapWsdlResource loaded = new SoapWsdlResource(wsdl.toUri().toURL());

            // WSDL type part 名称直接对应有服务命名空间的 Body accessor。
            SoapOperation operation = loaded.definition().operation("echo");
            SoapBody inputBody = operation.inputBody();
            assertNull(inputBody.wrapper());
            assertEquals("echoArg0", inputBody.elements().get(0).getLocalPart());
            Element part = (Element) document.getElementsByTagNameNS(WSDL, "part").item(0);
            assertEquals("echoArg0", part.getAttribute("name"));
            assertEquals("tns:Type1", part.getAttribute("type"));
        } finally {
            Files.deleteIfExists(wsdl);
        }
    }

    @Test
    public void generatedSoap12DocumentEncodedContractOmitsWsdlEncodingStyle() throws Exception {
        // SOAP 1.2 document 绑定可以选择 encoded，但不能声明 WSDL encodingStyle 属性。
        byte[] definition = this.generated(ArrayService.class, "1.2", "document");
        Document document = this.document(definition);
        Path wsdl = Files.createTempFile("meteor-document-encoded12", ".wsdl");
        try {
            Files.write(wsdl, definition);
            SoapWsdlResource loaded = new SoapWsdlResource(wsdl.toUri().toURL());

            // 元素 part 保留全局 QName，运行时编码规则由标准版本推断。
            Element body = (Element) document.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/soap12/", //
                    "body").item(0);
            assertEquals("encoded", body.getAttribute("use"));
            assertFalse(body.hasAttribute("encodingStyle"));
            SoapOperation operation = loaded.definition().operation("echo");
            SoapBody inputBody = operation.inputBody();
            assertNull(inputBody.wrapper());
            assertEquals("http://www.w3.org/2003/05/soap-encoding", //
                    inputBody.encodingStyle());
        } finally {
            Files.deleteIfExists(wsdl);
        }
    }

    @Test
    public void documentEncodedCannotRequestWrappedJavaMapping() {
        // document/encoded 的 body roots 就是消息 accessors，显式 wrapped 与该映射不兼容。
        ServiceDomain<ArrayService> service = new ServiceDomain<>(ArrayService.class);
        service.putAliasName("soap", "EncodedSchema");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", //
                Map.of("soapUse", "encoded", "soapParameterStyle", "wrapped"));

        // 配置错误在生成契约时明确拒绝。
        URI endpoint = URI.create("http://localhost/service");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> SoapDefinitionResource.create(config, service).renderWsdl(endpoint));
        assertEquals("Document encoded SOAP requires bare parameters", failure.getMessage());
    }

    private byte[] generated(Class<?> type, String version, String style) throws Exception {
        ServiceDomain<?> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("soap", "EncodedSchema");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", //
                Map.of("soapVersion", version, "soapStyle", style, "soapUse", "encoded"));
        URI endpoint = URI.create("http://localhost/EncodedSchema");
        return SoapDefinitionResource.create(config, service).renderWsdl(endpoint);
    }

    private Document document(byte[] wsdl) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(wsdl));
    }

    private interface ArrayService {
        String[] echo(String[] values);
    }

    private interface MatrixService {
        int[][] echo(int[][] values);
    }

    private interface NestedCollectionService {
        List<List<String>> echo(List<List<String>> values);
    }

    private interface BeanService {
        CollectionBean echo(CollectionBean value);
    }

    public static class CollectionBean {
        public List<String> values;
    }
}
