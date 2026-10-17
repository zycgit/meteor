/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import javax.xml.XMLConstants;
import com.fasterxml.jackson.core.type.TypeReference;
import net.hasor.example.protocol.soap.MetXmlAmount;
import net.hasor.example.protocol.soap.MetXmlOrder;
import net.hasor.example.protocol.soap.MetXmlState;
import net.hasor.example.protocol.soap.codec.MetXmlBinaryText;
import net.hasor.example.protocol.soap.codec.MetXmlEncodedValue;
import net.hasor.example.protocol.soap.codec.MetXmlNullableValue;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class SoapXmlValueMappingTest {
    @Test
    public void literalSiblingsKeepTheirDistinctNamespaceIdentities() throws Exception {
        // 两个业务字段具有相同 localName，完整 QName 不同。
        SoapLiteralValueCodec codec = this.codec();
        MetXmlOrder value = this.order();
        Element target = this.target();

        // 正反向映射都不能让后出现的 name 覆盖前一个字段。
        codec.write(target, MetXmlOrder.class, value, new LinkedHashMap<>());
        MetXmlOrder decoded = (MetXmlOrder) codec.read(target, MetXmlOrder.class);
        assertEquals("buyer", decoded.getBuyer());
        assertEquals("delivery", decoded.getDelivery());
        assertEquals(1, target.getElementsByTagNameNS(MetXmlOrder.BUYER, "name").getLength());
        assertEquals(1, target.getElementsByTagNameNS(MetXmlOrder.DELIVERY, "name").getLength());
    }

    @Test
    public void literalRejectsKnownLocalNameInAnUnexpectedNamespace() throws Exception {
        // 完整报文在 RPC/literal 下也不能用错误命名空间伪装已知字段。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        codec.write(target, MetXmlOrder.class, this.order(), new LinkedHashMap<>());
        Element buyer = (Element) target.getElementsByTagNameNS(MetXmlOrder.BUYER, "name").item(0);
        target.getOwnerDocument().renameNode(buyer, "urn:unrelated", "wrong:name");

        // 即使该字段是可选的，也必须显式报告命名空间不匹配。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> codec.read(target, MetXmlOrder.class));
        assertTrue(failure.getMessage().contains("namespace"));
    }

    @Test
    public void literalNamespacedAttributesRoundTrip() throws Exception {
        // 业务属性使用独立命名空间。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        MetXmlOrder value = this.order();
        value.setId("order-17");

        // 属性与生成契约使用相同 QName，不退化成普通子元素。
        codec.write(target, MetXmlOrder.class, value, new LinkedHashMap<>());
        assertEquals("order-17", target.getAttributeNS(MetXmlOrder.ATTRIBUTES, "id"));
        assertEquals("order-17", ((MetXmlOrder) codec.read(target, MetXmlOrder.class)).getId());
    }

    @Test
    public void literalSimpleContentPreservesDecimalTextAndAttributes() throws Exception {
        // 金额使用文本，币种使用属性，并包含必须按十进制输出的指数值。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        MetXmlAmount amount = new MetXmlAmount();
        amount.setValue(new BigDecimal("1E+5"));
        amount.setCurrency("CNY");

        // simpleContent 不生成 value 子元素，数值仍能完整还原。
        codec.write(target, MetXmlAmount.class, amount, new LinkedHashMap<>());
        assertEquals("100000", target.getTextContent());
        assertEquals("CNY", target.getAttribute("currency"));
        MetXmlAmount decoded = (MetXmlAmount) codec.read(target, MetXmlAmount.class);
        assertEquals(0, amount.getValue().compareTo(decoded.getValue()));
        assertEquals("CNY", decoded.getCurrency());
    }

    @Test
    public void literalBinarySimpleContentRetainsItsAttachmentLocation() throws Exception {
        // 真实二进制文本和二进制属性同时存在，只有元素文本允许变为 MTOM 附件。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        MetXmlBinaryText value = new MetXmlBinaryText();
        value.value = new byte[] { 1, 2, 3 };
        value.signature = new byte[] { 4, 5 };
        Map<Element, byte[]> binaries = new LinkedHashMap<>();

        // 候选位置是最终 SOAP 文档中的原元素，属性始终保存内联 base64。
        codec.write(target, MetXmlBinaryText.class, value, binaries);
        assertEquals("BAU=", target.getAttribute("signature"));
        assertEquals(1, binaries.size());
        assertArrayEquals(value.value, binaries.get(target));
        MetXmlBinaryText decoded = (MetXmlBinaryText) codec.read(target, MetXmlBinaryText.class);
        assertArrayEquals(value.value, decoded.value);
        assertArrayEquals(value.signature, decoded.signature);
    }

    @Test
    public void literalWrappedNullCollectionRemainsNull() throws Exception {
        // 集合容器允许 nil，成员的可空性与容器无关。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        MetXmlOrder value = this.order();
        value.setLines(null);

        // nil 容器不会被反序列化成空集合或单个 null 成员。
        codec.write(target, MetXmlOrder.class, value, new LinkedHashMap<>());
        assertNull(((MetXmlOrder) codec.read(target, MetXmlOrder.class)).getLines());
        Element wrapper = (Element) target.getElementsByTagNameNS(MetXmlOrder.TYPES, "lines").item(0);
        assertEquals("true", wrapper.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil"));
    }

    @Test
    public void literalWrappedEmptyCollectionRemainsEmpty() throws Exception {
        // 空容器与不存在的容器有不同含义。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        MetXmlOrder value = this.order();
        value.setLines(List.of());

        // 空集合保留显式容器，不退化成 null。
        codec.write(target, MetXmlOrder.class, value, new LinkedHashMap<>());
        assertEquals(List.of(), ((MetXmlOrder) codec.read(target, MetXmlOrder.class)).getLines());
    }

    @Test
    public void literalWrappedCollectionCanContainASingleNilItem() throws Exception {
        // 容器存在，其中只有一个可空成员。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        MetXmlOrder value = this.order();
        value.setLines(Arrays.asList((String) null));

        // 单个 null 成员与 nil 容器、空容器保持区别。
        codec.write(target, MetXmlOrder.class, value, new LinkedHashMap<>());
        List<String> decoded = ((MetXmlOrder) codec.read(target, MetXmlOrder.class)).getLines();
        assertEquals(1, decoded.size());
        assertNull(decoded.get(0));
    }

    @Test
    public void literalOptionalNonNillableNullIsOmitted() throws Exception {
        // 元素可选但不可 nil，空值的合法表示是省略它。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        MetXmlNullableValue value = new MetXmlNullableValue();

        // 不生成 xsi:nil 导致对端按照 nonnillable 契约拒绝。
        codec.write(target, MetXmlNullableValue.class, value, new LinkedHashMap<>());
        assertEquals(0, target.getElementsByTagName("optional").getLength());
        assertNull(((MetXmlNullableValue) codec.read(target, MetXmlNullableValue.class)).optional);
    }

    @Test
    public void literalRequiredNillableNullHasAnExplicitElement() throws Exception {
        // required 约束要求元素出现，nillable 允许其内容为空。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        MetXmlNullableValue value = new MetXmlNullableValue();
        value.required = null;

        // null 使用一个 nil 元素表示，读取后仍是 null。
        codec.write(target, MetXmlNullableValue.class, value, new LinkedHashMap<>());
        Element required = (Element) target.getElementsByTagName("required").item(0);
        assertEquals("true", required.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil"));
        assertNull(((MetXmlNullableValue) codec.read(target, MetXmlNullableValue.class)).required);
    }

    @Test
    public void literalMissingRequiredElementIsRejected() throws Exception {
        // 只有可选元素，缺少 required 元素。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();

        // 构造器默认值不能掩盖传入报文缺少必需字段的问题。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> codec.read(target, MetXmlNullableValue.class));
        assertTrue(failure.getMessage().contains("required"));
    }

    @Test
    public void literalEmptyWrapperCannotSatisfyARequiredItem() {
        // 容器允许为空引用，但出现的容器要求至少一个成员。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        MetXmlNullableValue value = new MetXmlNullableValue();
        value.entries = List.of();

        // required 成员约束独立于容器的 required 约束。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> codec.write(target, MetXmlNullableValue.class, value, new LinkedHashMap<>()));
        assertTrue(failure.getMessage().contains("entry"));
    }

    @Test
    public void literalTransientPropertyIsNotSent() throws Exception {
        // 本地令牌不属于 SOAP 契约。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        MetXmlOrder value = this.order();
        value.setInternalToken("private-token");

        // transient 属性不出现在报文中，也不因反射访问而泄漏。
        codec.write(target, MetXmlOrder.class, value, new LinkedHashMap<>());
        assertFalse(target.getTextContent().contains("private-token"));
        assertNull(((MetXmlOrder) codec.read(target, MetXmlOrder.class)).getInternalToken());
    }

    @Test
    public void literalEnumAliasRoundTripsWithoutJacksonAnnotations() throws Exception {
        // 枚举使用 Meteor 定义的 XML 字面值。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();

        // 编解码共享相同的别名映射。
        codec.write(target, MetXmlState.class, MetXmlState.READY, new LinkedHashMap<>());
        assertEquals("ready-to-send", target.getTextContent());
        assertEquals(MetXmlState.READY, codec.read(target, MetXmlState.class));
    }

    @Test
    public void literalDateKeepsTheExistingEpochMillisContract() throws Exception {
        // 时间仍遵循既有 xsd:long 契约，不自动转换成 ISO 日期字符串。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        Date value = new Date(123456789L);

        // 日期的毫秒值双向保持一致。
        codec.write(target, Date.class, value, new LinkedHashMap<>());
        assertEquals("123456789", target.getTextContent());
        assertEquals(value, codec.read(target, Date.class));
    }

    @Test
    public void literalCharacterArrayRemainsOneXmlString() throws Exception {
        // char[] 是标量文本，不能按普通数组生成多个 item。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        char[] value = { 'a', '\u4e2d' };

        // 文本与字符数组双向对应。
        codec.write(target, char[].class, value, new LinkedHashMap<>());
        assertEquals("a\u4e2d", target.getTextContent());
        assertArrayEquals(value, (char[]) codec.read(target, char[].class));
    }

    @Test
    public void literalCircularObjectFailsBeforeRecursiveEncodingOverflows() {
        // recursive 类型可以发布，但 literal 实例不能用无限 XML 表示自身引用。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        MetXmlEncodedValue value = new MetXmlEncodedValue();
        value.next = value;

        // 明确失败而不是发生栈溢出；Encoding 对象图由另一组测试覆盖。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> codec.write(target, MetXmlEncodedValue.class, value, new LinkedHashMap<>()));
        assertTrue(failure.getMessage().contains("circular"));
    }

    @Test
    public void literalUnknownBase64CharactersAreRejected() {
        // MIME 解码器会忽略非法字符，SOAP base64 值必须拒绝它们。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        target.setTextContent("!!!!");

        // 不能将无效业务数据静默转换成空二进制。
        assertThrows(IllegalArgumentException.class, () -> codec.read(target, byte[].class));
    }

    @Test
    public void literalInvalidNilLexicalValueIsRejected() {
        // xsi:nil 采用 XML Schema boolean 字面值，大小写不能随意变化。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        target.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:nil", "TRUE");

        // RPC/literal 没有文档级 JAXP 校验时，值解码仍保持协议约束。
        assertThrows(IllegalArgumentException.class, () -> codec.read(target, String.class));
    }

    @Test
    public void externalRootCollectionDoesNotDiscardItsDeclaredItemNames() throws Exception {
        // 外部契约可以使用 entry 作为根集合中的成员名称。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        SoapXmlUtils.append(target, "urn:external", "entry").setTextContent("first");
        SoapXmlUtils.append(target, "urn:external", "entry").setTextContent("second");
        Type type = new TypeReference<List<String>>() {
        }.getType();

        // 根集合本身没有属性级 item 注解，按内容类型读取实际成员而不丢弃它们。
        assertEquals(List.of("first", "second"), codec.read(target, type));
    }

    @Test
    public void literalOptionalAttributeRejectsAnUnexpectedNamespace() {
        // 属性本身可选，错误命名空间不能伪装成该业务字段已缺省。
        SoapLiteralValueCodec codec = this.codec();
        Element target = this.target();
        target.setAttributeNS("urn:unrelated", "wrong:identifier", "changed");

        // RPC/literal 未经过文档 Schema 校验时，也不能静默吞掉该值。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> codec.read(target, MetXmlEncodedValue.class));
        assertTrue(failure.getMessage().contains("attribute namespace"));
    }

    private SoapLiteralValueCodec codec() {
        return new SoapLiteralValueCodec(SoapMessageCodec.newXmlMapper());
    }

    private MetXmlOrder order() {
        MetXmlOrder value = new MetXmlOrder();
        value.setId("order-1");
        value.setBuyer("buyer");
        value.setDelivery("delivery");
        value.setState(MetXmlState.READY);
        return value;
    }

    private Element target() {
        Document document = SoapXmlUtils.newDocument();
        Element target = document.createElementNS("urn:test:xml", "value");
        document.appendChild(target);
        return target;
    }
}
