/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.util.Map;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.example.protocol.soap.MetXmlMapValue;
import net.hasor.example.protocol.soap.MetXmlWrappedUser;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.*;

public class SoapNestedDtoMappingTest {
    @Test
    public void singleValueContainerUsesAnOrdinaryNestedDto() throws Exception {
        // Java users 对象承载 XML users 元素，其 value 属性通过公共名称映射为 user。
        MetXmlWrappedUser input = new MetXmlWrappedUser();
        input.users = new MetXmlWrappedUser.Users();
        input.users.value = new MetXmlWrappedUser.User();
        input.users.value.id = "user-1";
        Element target = this.target();
        SoapLiteralValueCodec codec = this.codec();

        // 不需要为标量包装提供另一套注解或运行期虚拟容器。
        codec.write(target, MetXmlWrappedUser.class, input, null);
        Element users = (Element) target.getFirstChild();
        assertEquals("users", users.getLocalName());
        assertEquals("user", users.getFirstChild().getLocalName());
        MetXmlWrappedUser result = (MetXmlWrappedUser) codec.read(target, MetXmlWrappedUser.class);
        assertEquals("user-1", result.users.value.id);
    }

    @Test
    public void attributeGroupAndDynamicAttributesShareTheDeclaredDtoElement() throws Exception {
        // settings 本身是普通 DTO，静态属性和动态属性 Map 都属于这个元素。
        MetXmlMapValue input = new MetXmlMapValue();
        input.id = "record-1";
        input.settings.version = "1";
        input.settings.values = Map.of("limit", 30);
        Element target = this.target();
        SoapLiteralValueCodec codec = this.codec();

        // 属性 Map 不新增容器或读取时的业务类型猜测。
        codec.write(target, MetXmlMapValue.class, input, null);
        Element settings = (Element) target.getElementsByTagName("settings").item(0);
        assertEquals("1", settings.getAttribute("version"));
        assertEquals("30", settings.getAttribute("limit"));
        MetXmlMapValue result = (MetXmlMapValue) codec.read(target, MetXmlMapValue.class);
        assertEquals("30", result.settings.values.get("limit"));
    }

    @Test
    public void nullableNestedContainerDoesNotCreateAnEmptyObject() throws Exception {
        // 未设置 users 对象，集合和单值都遵循其真实 Java 声明。
        MetXmlWrappedUser input = new MetXmlWrappedUser();
        Element target = this.target();
        SoapLiteralValueCodec codec = this.codec();

        // 回读仍为 null，不根据包装名称制造 DTO 实例。
        codec.write(target, MetXmlWrappedUser.class, input, null);
        MetXmlWrappedUser result = (MetXmlWrappedUser) codec.read(target, MetXmlWrappedUser.class);
        assertNull(result.users);
    }

    @Test
    public void jacksonCollectionWrapperDoesNotCreateAnImplicitScalarContainer() {
        // Jackson 的集合包装不能用来声明标量的业务对象层级。
        assertThrows(IllegalArgumentException.class, () -> {
            this.codec().write(this.target(), ScalarWrapper.class, new ScalarWrapper(), null);
        });
    }

    @Test
    public void jacksonCollectionWrapperDoesNotCreateAnImplicitAttributeGroup() {
        // attribute 的所属对象必须在 Java DTO 中存在。
        assertThrows(IllegalArgumentException.class, () -> {
            this.codec().write(this.target(), AttributeWrapper.class, new AttributeWrapper(), null);
        });
    }

    private Element target() {
        Document document = SoapXmlUtils.newDocument();
        Element root = document.createElement("value");
        document.appendChild(root);
        return root;
    }

    private SoapLiteralValueCodec codec() {
        return new SoapLiteralValueCodec(new SoapXmlMapping(SoapXmlMapping.newMapper()));
    }

    public static class ScalarWrapper {
        @JacksonXmlElementWrapper(localName = "container")
        public String value;
    }

    public static class AttributeWrapper {
        @JacksonXmlElementWrapper(localName = "container")
        @JacksonXmlProperty(isAttribute = true)
        public String value;
    }
}
