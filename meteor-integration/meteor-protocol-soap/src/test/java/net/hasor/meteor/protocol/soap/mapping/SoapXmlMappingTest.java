/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.mapping;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.meteor.protocol.soap.annotation.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoapXmlMappingTest {
    private final XmlMapper      mapper  = SoapXmlMapping.newMapper();
    private final SoapXmlMapping mapping = new SoapXmlMapping(this.mapper);

    @Test
    public void unannotatedFieldKeepsItsUnqualifiedName() {
        // 未标注的公开字段沿用原有 Java Bean 约定。
        JavaType javaType = this.mapper.constructType(PlainBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // XML 属性名不额外继承服务或类型命名空间。
        assertEquals(javaType, type.getJavaType());
        assertEquals(1, type.getProperties().size());
        assertEquals(new QName("", "message"), type.getProperties().get(0).getName());
        assertFalse(type.getProperties().get(0).isAttribute());
    }

    @Test
    public void unannotatedAccessorReadsAndWritesTheBean() throws Exception {
        // 普通 getter/setter 仍是可读写属性，不要求业务类新增注解。
        JavaType javaType = this.mapper.constructType(AccessorBean.class);
        SoapXmlType type = this.mapping.type(javaType);
        SoapXmlProperty property = type.getProperties().get(0);
        Object bean = type.newInstance();

        // 映射通过真实访问器写入并读出业务值。
        property.write(bean, "hello");
        assertEquals("hello", property.read(bean));
        assertEquals("message", property.getJavaName());
        assertEquals(String.class, property.getJavaType().getRawClass());
    }

    @Test
    public void matchingFieldAndGetterAnnotationsDefineOneProperty() {
        // 同一个 Java 属性在字段和 getter 上声明一致的 XML 名称。
        JavaType javaType = this.mapper.constructType(MatchingAccessors.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 字段与访问器合并为一个 XML 属性。
        assertEquals(1, type.getProperties().size());
        assertEquals(new QName("urn:message", "text"), type.getProperties().get(0).getName());
    }

    @Test
    public void conflictingFieldAndGetterNamesAreRejected() {
        // 同一属性的字段和 getter 不能各自指定不同的 XML 名称。
        JavaType javaType = this.mapper.constructType(ConflictingAccessors.class);

        // 配置错误在构建映射时暴露，不留到收到报文时随机选择。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void conflictingFieldAndSetterNamesAreRejected() {
        // setter 与字段也必须描述同一个 XML 属性。
        JavaType javaType = this.mapper.constructType(ConflictingSetter.class);

        // 读写方向不能各自使用不同的 QName。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void conflictingInterfaceAccessorNamesAreRejected() {
        // 注解声明在接口上，具体 Bean 不重复标注访问器。
        JavaType javaType = this.mapper.constructType(ConflictingInterfaceBean.class);

        // 继承的 getter/setter 名称冲突不能被 Jackson 合并过程隐藏。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void matchingInterfaceAccessorNamesDefineOneProperty() {
        // 接口的 getter/setter 对同一个 XML QName 达成一致。
        JavaType javaType = this.mapper.constructType(MatchingInterfaceBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 实现类无需复制接口声明，属性名称和命名空间仍完整保留。
        assertEquals(1, type.getProperties().size());
        assertEquals(new QName("urn:interface", "text"), type.getProperties().get(0).getName());
    }

    @Test
    public void meteorElementOverridesJacksonXmlProperty() {
        // 已有 Jackson 名称和命名空间保留为兼容信息，显式 Meteor 映射优先。
        JavaType javaType = this.mapper.constructType(MeteorOverridesJacksonXml.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 不让 Jackson 的属性标记把 Meteor 元素变成 XML attribute。
        SoapXmlProperty property = type.getProperties().get(0);
        assertEquals(new QName("urn:meteor", "message"), property.getName());
        assertFalse(property.isAttribute());
    }

    @Test
    public void meteorElementOverridesJacksonJsonName() {
        // Java 逻辑属性可保留 JsonProperty 名称，XML QName 由 Meteor 显式指定。
        JavaType javaType = this.mapper.constructType(MeteorOverridesJacksonJson.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 编解码无需把 Java 逻辑键与 XML 名称混用。
        assertEquals(new QName("urn:meteor", "message"), type.getProperties().get(0).getName());
    }

    @Test
    public void jacksonXmlPropertyRemainsSupportedWithoutMeteorOverride() {
        // 兼容业务已有的 Jackson XML 元素注解。
        JavaType javaType = this.mapper.constructType(JacksonXmlBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 名称和命名空间都保留在映射中。
        assertEquals(new QName("urn:jackson", "text"), type.getProperties().get(0).getName());
    }

    @Test
    public void annotatedTypeExposesItsSchemaNameAndNamespace() {
        // 类型名称与元素 QName 分开声明。
        JavaType javaType = this.mapper.constructType(NamedBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // XSD 生成器可以直接使用声明的命名类型。
        assertEquals("MessageType", type.getName());
        assertEquals("urn:types", type.getNamespace());
    }

    @Test
    public void propertyOrderUsesJavaPropertyNames() {
        // propOrder 指定 Java 属性名，XML 名称可以完全不同。
        JavaType javaType = this.mapper.constructType(OrderedBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // Schema 顺序和消息写入顺序共享同一份属性列表。
        List<String> names = type.getProperties().stream() //
                .map(SoapXmlProperty::getJavaName) //
                .toList();
        assertEquals(List.of("second", "first"), names);
    }

    @Test
    public void unknownPropertyOrderEntryIsRejected() {
        // 误写的 propOrder 名称不能被静默忽略。
        JavaType javaType = this.mapper.constructType(UnknownOrderBean.class);

        // 避免生成的 XSD 与用户声明顺序不一致。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void incompletePropertyOrderIsRejected() {
        // 显式顺序应列出全部元素，不能让遗漏字段依赖反射顺序补齐。
        JavaType javaType = this.mapper.constructType(IncompleteOrderBean.class);

        // 顺序声明与实际契约不一致时直接报错。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void duplicatedPropertyOrderEntryIsRejected() {
        // 同一个 Java 属性不能在 sequence 中重复出现。
        JavaType javaType = this.mapper.constructType(DuplicateOrderBean.class);

        // 重复项不应覆盖或隐藏其它元素。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void attributeNeedNotAppearInElementOrder() {
        // XML attribute 不参与子元素 sequence 的顺序。
        JavaType javaType = this.mapper.constructType(OrderedAttributeBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 只列出元素仍是完整的顺序声明。
        assertEquals(2, type.getProperties().size());
        long attributes = type.getProperties().stream().filter(SoapXmlProperty::isAttribute).count();
        assertEquals(1L, attributes);
    }

    @Test
    public void parameterizedCollectionKeepsItsItemType() {
        // 业务集合的泛型需要传给 XSD 生成器和消息解码器。
        JavaType javaType = this.mapper.constructType(GenericCollectionBean.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        // 不把集合内容退化为 Object 或字符串。
        JavaType collectionType = property.getJavaType();
        assertEquals(List.class, collectionType.getRawClass());
        assertEquals(Long.class, collectionType.getContentType().getRawClass());
        assertTrue(this.mapping.type(collectionType).getProperties().isEmpty());
    }

    @Test
    public void annotatedPrivateFieldIsReadableAndWritable() throws Exception {
        // Meteor 注解明确把 private 字段纳入 XML 映射。
        JavaType javaType = this.mapper.constructType(PrivateFieldBean.class);
        SoapXmlType type = this.mapping.type(javaType);
        SoapXmlProperty property = type.getProperties().get(0);
        Object bean = type.newInstance();

        // 字段无 getter/setter 仍可通过声明的映射访问。
        property.write(bean, "private-value");
        assertEquals("private-value", property.read(bean));
        assertEquals(new QName("", "text"), property.getName());
    }

    @Test
    public void elementCarriesRequiredAndNillableConstraints() {
        // 元素可以要求必须出现，并禁止 xsi:nil。
        JavaType javaType = this.mapper.constructType(RequiredElementBean.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        // 定义生成和消息校验读取同一组约束。
        assertTrue(property.isRequired());
        assertFalse(property.isNillable());
    }

    @Test
    public void attributeCarriesItsOwnQNameAndRequirement() {
        // XML attribute 的命名空间不依赖同名元素或所属类型。
        JavaType javaType = this.mapper.constructType(AttributeBean.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        // 属性必须出现的约束与真实 QName 一起保留。
        assertTrue(property.isAttribute());
        assertTrue(property.isRequired());
        assertEquals(new QName("urn:attributes", "code"), property.getName());
    }

    @Test
    public void namespaceDeclarationCannotBeABusinessAttribute() {
        // xmlns 是命名空间声明，不能借业务字段伪装为普通 attribute。
        JavaType javaType = this.mapper.constructType(NamespaceDeclarationBean.class);

        // 发布映射时拒绝会改变报文命名空间语义的声明。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void reservedNamespaceCannotBeAnElementNamespace() {
        // XMLNS 保留命名空间不承载业务元素。
        JavaType javaType = this.mapper.constructType(ReservedNamespaceBean.class);

        // 防止生成合法 QName 外观、但不能形成 XML 文档的契约。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void textAndAttributesFormSimpleContent() {
        // simpleContent 允许一个文本值配合若干 attribute。
        JavaType javaType = this.mapper.constructType(TextWithAttribute.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 两个成员承担不同的 XML 角色，不生成额外文本子元素。
        long textCount = type.getProperties().stream().filter(SoapXmlProperty::isText).count();
        long attributeCount = type.getProperties().stream().filter(SoapXmlProperty::isAttribute).count();
        assertEquals(1L, textCount);
        assertEquals(1L, attributeCount);
    }

    @Test
    public void textMixedWithChildElementsIsRejected() {
        // 第一版映射不把文本与子元素混合内容当作普通 Bean。
        JavaType javaType = this.mapper.constructType(MixedContentBean.class);

        // 拒绝会丢失文本顺序的信息模型。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void severalTextPropertiesAreRejected() {
        // 一个 simpleContent 值不能同时写到两个 Java 属性。
        JavaType javaType = this.mapper.constructType(DuplicateTextBean.class);

        // 在定义阶段消除反序列化的歧义。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void collectionWrapperAndItemKeepIndependentQNames() {
        // 集合容器与内部条目分别指定名称和命名空间。
        JavaType javaType = this.mapper.constructType(WrappedCollectionBean.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        // wrapper 的出现和 nil 约束不覆盖 item 本身的约束。
        assertEquals(new QName("urn:containers", "messages"), property.getWrapper());
        assertEquals(new QName("urn:items", "message"), property.getName());
        assertTrue(property.isWrapperRequired());
        assertFalse(property.isWrapperNillable());
        assertFalse(property.isRequired());
        assertTrue(property.isNillable());
    }

    @Test
    public void primitiveArrayValueMayBeNil() {
        // int[] 是可空的 Java 引用，元数据不把数组本身误判为 primitive。
        JavaType javaType = this.mapper.constructType(PrimitiveArrayBean.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        // 数组条目的 primitive 类型仍保留，整个数组的 nil 约束独立表示。
        assertEquals(int.class, property.getJavaType().getContentType().getRawClass());
        assertTrue(property.isNillable());
        assertNull(property.getWrapper());
    }

    @Test
    public void primitiveArrayValueAndWrapperKeepIndependentNilConstraints() {
        // 集合包装层与属性值分别声明约束，不由 primitive 内容类型覆盖。
        JavaType javaType = this.mapper.constructType(WrappedPrimitiveArrayBean.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        // 包装层允许 nil，属性值则保留显式配置的非空约束。
        assertEquals(new QName("", "values"), property.getWrapper());
        assertTrue(property.isWrapperNillable());
        assertFalse(property.isNillable());
    }

    @Test
    public void wrapperOnScalarPropertyIsRejected() {
        // 普通字符串不是集合，不能额外声明集合 wrapper。
        JavaType javaType = this.mapper.constructType(ScalarWrapperBean.class);

        // 明确拒绝不成立的结构，而不是偷偷改写其类型。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void complexAttributeIsRejected() {
        // XML attribute 无法承载一个带子字段的 Bean。
        JavaType javaType = this.mapper.constructType(ComplexAttributeBean.class);

        // 复杂值必须选择元素映射。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void sameLocalNameInDifferentNamespacesIsNotMerged() {
        // 两个不同 Java 属性共享 local name，但拥有不同 namespace。
        JavaType javaType = this.mapper.constructType(NamespacedSiblings.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 映射保留完整 QName，不依赖只比较 local name 的 Jackson XML 反序列化。
        List<QName> names = type.getProperties().stream() //
                .map(SoapXmlProperty::getName) //
                .toList();
        assertEquals(2, names.size());
        assertTrue(names.contains(new QName("urn:first", "value")));
        assertTrue(names.contains(new QName("urn:second", "value")));
    }

    @Test
    public void duplicateElementQNameIsRejected() {
        // 两个 Java 属性不能占用同一个 XML 元素 QName。
        JavaType javaType = this.mapper.constructType(DuplicateElementBean.class);

        // 避免后写字段覆盖先写字段。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void attributeAndElementMayShareTheSameQName() {
        // XML 元素和 attribute 属于不同的成员空间。
        JavaType javaType = this.mapper.constructType(ElementAndAttributeBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 同名元素和同名属性同时保留。
        assertEquals(2, type.getProperties().size());
        long attributes = type.getProperties().stream().filter(SoapXmlProperty::isAttribute).count();
        assertEquals(1L, attributes);
    }

    @Test
    public void meteorTransientRemovesTheProperty() {
        // Java 侧辅助字段显式排除，不进入 Schema 或消息。
        JavaType javaType = this.mapper.constructType(TransientBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 仅保留实际参与 XML 契约的业务字段。
        assertEquals(1, type.getProperties().size());
        assertEquals("message", type.getProperties().get(0).getJavaName());
    }

    @Test
    public void jacksonIgnoreRemainsSupported() {
        // 未使用 Meteor 覆盖时保留现有 JsonIgnore 行为。
        JavaType javaType = this.mapper.constructType(JacksonIgnoreBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // Java 辅助字段不因为新增映射系统而重新暴露。
        assertEquals(1, type.getProperties().size());
        assertEquals("message", type.getProperties().get(0).getJavaName());
    }

    @Test
    public void meteorElementIncludesAnOtherwiseIgnoredProperty() {
        // 显式 XML 契约可以纳入仅在 JSON 中忽略的字段。
        JavaType javaType = this.mapper.constructType(MeteorOverridesIgnoreBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // JsonIgnore 不应覆盖 Meteor 明确声明的 XML 元素。
        assertEquals(1, type.getProperties().size());
        assertEquals(new QName("", "message"), type.getProperties().get(0).getName());
    }

    @Test
    public void meteorTransientExcludesAnExplicitElement() {
        // 同一字段同时声明元素与排除时，Transient 表示明确不参与 XML。
        JavaType javaType = this.mapper.constructType(TransientElementBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 排除指令不能因为该字段还有元素名称而失效。
        assertEquals(1, type.getProperties().size());
        assertEquals("message", type.getProperties().get(0).getJavaName());
    }

    @Test
    public void meteorElementOverridesJsonReadOnlyAccess() {
        // JSON 只读声明不限制显式 XML 元素的双向映射。
        JavaType javaType = this.mapper.constructType(MeteorOverridesReadOnlyBean.class);
        SoapXmlType type = this.mapping.type(javaType);
        SoapXmlProperty property = type.getProperties().get(0);
        Object bean = type.newInstance();

        // SOAP 仍能把对端值写回同一个业务属性。
        property.write(bean, "received");
        assertEquals("received", property.read(bean));
    }

    @Test
    public void getterOnlyPartNameDoesNotRequireWritableBeanMapping() {
        // 多 part 输出聚合只需读取 getter，不要求对象具有默认构造或 setter。
        JavaType javaType = this.mapper.constructType(GetterOnlyPartBean.class);
        List<BeanPropertyDefinition> properties = this.mapper.getSerializationConfig() //
                .introspect(javaType) //
                .findProperties();

        // 单向名称解析保留 getter 上的完整 QName。
        assertEquals(1, properties.size());
        QName name = this.mapping.propertyName(properties.get(0));
        assertEquals(new QName("urn:parts", "status"), name);
    }

    @Test
    public void setterOnlyPartNameCanBeResolvedFromSerializationMetadata() {
        // 输出方向仍可列出仅有 setter 的属性，名称解析不应提前触发可读性校验。
        JavaType javaType = this.mapper.constructType(SetterOnlyPartBean.class);
        List<BeanPropertyDefinition> properties = this.mapper.getSerializationConfig() //
                .introspect(javaType) //
                .findProperties();

        // 使用真实 setter 取得声明归属，读取限制留给聚合输出操作处理。
        assertEquals(1, properties.size());
        assertFalse(properties.get(0).couldSerialize());
        assertEquals(new QName("message"), this.mapping.propertyName(properties.get(0)));
    }

    @Test
    public void writeOnlyPartNameDoesNotRequireSerializationPrimaryMember() {
        // WRITE_ONLY 在输出元数据中没有主读取成员，仍需保留可定位的属性名称。
        JavaType javaType = this.mapper.constructType(WriteOnlyPartBean.class);
        List<BeanPropertyDefinition> properties = this.mapper.getSerializationConfig() //
                .introspect(javaType) //
                .findProperties();

        // 不因单向访问约束丢失名称，也不绕过其只写声明。
        assertEquals(1, properties.size());
        assertFalse(properties.get(0).couldSerialize());
        assertEquals(new QName("message"), this.mapping.propertyName(properties.get(0)));
    }

    @Test
    public void meteorEnumValueControlsTheWireValue() {
        // 枚举常量可指定稳定的 XML 字面量。
        String value = this.mapping.enumValue(MeteorState.READY);

        // 读写两方向使用同一个别名表。
        assertEquals("ready-for-use", value);
        assertEquals(MeteorState.READY, this.mapping.enumValue(MeteorState.class, value));
    }

    @Test
    public void unannotatedEnumUsesTheJavaConstantName() {
        // 无显式别名时，保留已有枚举常量名称。
        String value = this.mapping.enumValue(PlainState.READY);

        // 读取与写入采用同一个默认字面量。
        assertEquals("READY", value);
        assertEquals(PlainState.READY, this.mapping.enumValue(PlainState.class, value));
    }

    @Test
    public void jacksonEnumAliasRemainsSupported() {
        // 已有 JsonProperty 枚举别名不要求迁移。
        String value = this.mapping.enumValue(JacksonState.READY);

        // 兼容旧的 XML 枚举字面量。
        assertEquals("jackson-ready", value);
        assertEquals(JacksonState.READY, this.mapping.enumValue(JacksonState.class, value));
    }

    @Test
    public void meteorEnumAliasOverridesJacksonAlias() {
        // 同一常量同时带有两套别名时，以显式 Meteor XML 配置为准。
        String value = this.mapping.enumValue(OverrideState.READY);

        // 两个方向都采用 Meteor 别名，不把 Jackson 别名混入契约。
        assertEquals("meteor-ready", value);
        assertEquals(OverrideState.READY, this.mapping.enumValue(OverrideState.class, value));
    }

    @Test
    public void duplicateEnumAliasesAreRejected() {
        // 两个枚举常量不能共享同一个 XML 字面量。
        JavaType javaType = this.mapper.constructType(DuplicateState.class);

        // 定义阶段拒绝不能确定反向映射的枚举。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void unknownEnumLiteralIsRejected() {
        // 对端传入不在枚举契约内的值。
        String literal = "does-not-exist";

        // 不默认为某个业务状态。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.enumValue(MeteorState.class, literal));
    }

    @Test
    public void unsupportedJacksonIdentityMappingIsRejected() {
        // 对象 ID 语义不属于普通 XSD 值映射。
        JavaType javaType = this.mapper.constructType(IdentityBean.class);

        // 不允许 Schema 与 Jackson 实际输出的结构背离。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void customJsonValueEnumMappingIsRejected() {
        // 自定义 JsonValue 可能把枚举输出为任意结构，不能冒充 XSD 枚举字面量。
        JavaType javaType = this.mapper.constructType(CustomValueState.class);

        // 定义阶段报告不支持的枚举映射。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void beanWithoutDefaultConstructorIsRejected() {
        // XML 反序列化需要能创建业务 Bean。
        JavaType javaType = this.mapper.constructType(ConstructorOnlyBean.class);

        // 构建阶段暴露不可实例化类型。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    public static class PlainBean {
        public String message;
    }

    public static class AccessorBean {
        private String message;

        public String getMessage() {
            return this.message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class MatchingAccessors {
        @MetXmlElement(name = "text", namespace = "urn:message")
        private String message;

        @MetXmlElement(name = "text", namespace = "urn:message")
        public String getMessage() {
            return this.message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class ConflictingAccessors {
        @MetXmlElement(name = "field")
        private String message;

        @MetXmlElement(name = "getter")
        public String getMessage() {
            return this.message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class ConflictingSetter {
        @MetXmlElement(name = "field")
        private String message;

        public String getMessage() {
            return this.message;
        }

        @MetXmlElement(name = "setter")
        public void setMessage(String message) {
            this.message = message;
        }
    }

    public interface ConflictingInterface {
        @MetXmlElement(name = "left")
        String getMessage();

        @MetXmlElement(name = "right")
        void setMessage(String message);
    }

    public static class ConflictingInterfaceBean implements ConflictingInterface {
        private String message;

        @Override
        public String getMessage() {
            return this.message;
        }

        @Override
        public void setMessage(String message) {
            this.message = message;
        }
    }

    public interface MatchingInterface {
        @MetXmlElement(name = "text", namespace = "urn:interface")
        String getMessage();

        @MetXmlElement(name = "text", namespace = "urn:interface")
        void setMessage(String message);
    }

    public static class MatchingInterfaceBean implements MatchingInterface {
        private String message;

        @Override
        public String getMessage() {
            return this.message;
        }

        @Override
        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class MeteorOverridesJacksonXml {
        @JacksonXmlProperty(localName = "legacy", namespace = "urn:jackson", isAttribute = true)
        @MetXmlElement(name = "message", namespace = "urn:meteor")
        public String message;
    }

    public static class MeteorOverridesJacksonJson {
        @JsonProperty("legacy")
        @MetXmlElement(name = "message", namespace = "urn:meteor")
        public String message;
    }

    public static class JacksonXmlBean {
        @JacksonXmlProperty(localName = "text", namespace = "urn:jackson")
        public String message;
    }

    @MetXmlType(name = "MessageType", namespace = "urn:types")
    public static class NamedBean {
        public String message;
    }

    @MetXmlType(propOrder = { "second", "first" })
    public static class OrderedBean {
        @MetXmlElement(name = "a")
        public String first;
        @MetXmlElement(name = "b")
        public String second;
    }

    @MetXmlType(propOrder = { "missing" })
    public static class UnknownOrderBean {
        public String message;
    }

    @MetXmlType(propOrder = { "first" })
    public static class IncompleteOrderBean {
        public String first;
        public String second;
    }

    @MetXmlType(propOrder = { "message", "message" })
    public static class DuplicateOrderBean {
        public String message;
    }

    @MetXmlType(propOrder = { "message" })
    public static class OrderedAttributeBean {
        public String message;
        @MetXmlAttribute
        public String language;
    }

    public static class GenericCollectionBean {
        public List<Long> values;
    }

    public static class PrivateFieldBean {
        @MetXmlElement(name = "text")
        private String message;
    }

    public static class RequiredElementBean {
        @MetXmlElement(required = true, nillable = false)
        public String message;
    }

    public static class AttributeBean {
        @MetXmlAttribute(name = "code", namespace = "urn:attributes", required = true)
        public String code;
    }

    public static class NamespaceDeclarationBean {
        @MetXmlAttribute(name = "xmlns")
        public String namespace;
    }

    public static class ReservedNamespaceBean {
        @MetXmlElement(namespace = XMLConstants.XMLNS_ATTRIBUTE_NS_URI)
        public String message;
    }

    public static class TextWithAttribute {
        @MetXmlValue
        public String text;
        @MetXmlAttribute
        public String language;
    }

    public static class MixedContentBean {
        @MetXmlValue
        public String text;
        public String message;
    }

    public static class DuplicateTextBean {
        @MetXmlValue
        public String first;
        @MetXmlValue
        public String second;
    }

    public static class WrappedCollectionBean {
        @MetXmlElementWrapper(name = "messages", namespace = "urn:containers", required = true, nillable = false)
        @MetXmlElement(name = "message", namespace = "urn:items")
        public List<String> messages;
    }

    public static class PrimitiveArrayBean {
        public int[] values;
    }

    public static class WrappedPrimitiveArrayBean {
        @MetXmlElementWrapper(nillable = true)
        @MetXmlElement(name = "value", nillable = false)
        public int[] values;
    }

    public static class ScalarWrapperBean {
        @MetXmlElementWrapper
        public String message;
    }

    public static class ComplexAttributeBean {
        @MetXmlAttribute
        public PlainBean value;
    }

    public static class NamespacedSiblings {
        @MetXmlElement(name = "value", namespace = "urn:first")
        public String first;
        @MetXmlElement(name = "value", namespace = "urn:second")
        public String second;
    }

    public static class DuplicateElementBean {
        @MetXmlElement(name = "value")
        public String first;
        @MetXmlElement(name = "value")
        public String second;
    }

    public static class ElementAndAttributeBean {
        @MetXmlElement(name = "value")
        public String element;
        @MetXmlAttribute(name = "value")
        public String attribute;
    }

    public static class TransientBean {
        public String message;
        @MetXmlTransient
        public String localOnly;
    }

    public static class JacksonIgnoreBean {
        public String message;
        @JsonIgnore
        public String localOnly;
    }

    public static class MeteorOverridesIgnoreBean {
        @JsonIgnore
        @MetXmlElement
        public String message;
    }

    public static class TransientElementBean {
        public String message;
        @MetXmlTransient
        @MetXmlElement
        public String localOnly;
    }

    public static class MeteorOverridesReadOnlyBean {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @MetXmlElement
        public String message;
    }

    public static class GetterOnlyPartBean {
        private final String state;

        public GetterOnlyPartBean(String state) {
            this.state = state;
        }

        @MetXmlElement(name = "status", namespace = "urn:parts")
        public String getState() {
            return this.state;
        }
    }

    public static class SetterOnlyPartBean {
        public void setMessage(String message) {
        }
    }

    public static class WriteOnlyPartBean extends AccessorBean {
        @Override
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public String getMessage() {
            return super.getMessage();
        }
    }

    public enum MeteorState {
        @MetXmlEnumValue("ready-for-use") READY
    }

    public enum PlainState {
        READY
    }

    public enum JacksonState {
        @JsonProperty("jackson-ready") READY
    }

    public enum OverrideState {
        @JsonProperty("jackson-ready") @MetXmlEnumValue("meteor-ready") READY
    }

    public enum DuplicateState {
        @MetXmlEnumValue("same") FIRST,
        @MetXmlEnumValue("same") SECOND
    }

    public enum CustomValueState {
        READY;

        @JsonValue
        public String text() {
            return "custom-ready";
        }
    }

    @JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator.class, property = "id")
    public static class IdentityBean {
        public String message;
    }

    public static class ConstructorOnlyBean {
        public String message;

        public ConstructorOnlyBean(String message) {
            this.message = message;
        }
    }
}
