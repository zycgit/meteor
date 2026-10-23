/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.mapping;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import net.hasor.meteor.annotation.MetEnumValue;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetType;
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
        assertEquals(5, type.getProperties().get(0).getOrder());
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
    public void commonNamePreservesJacksonNamespaceAndAttributeKind() {
        // 公共名称覆盖 localName，Jackson 的命名空间和节点类别继续生效。
        JavaType javaType = this.mapper.constructType(MeteorOverridesJacksonXml.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 不把公共名称解释为元素类别声明。
        SoapXmlProperty property = type.getProperties().get(0);
        assertEquals(new QName("urn:jackson", "message"), property.getName());
        assertTrue(property.isAttribute());
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
    public void propertyOrderUsesIncreasingElementOrder() {
        // 每个元素独立声明数字顺序，不依赖 Java 属性名或 XML 名称。
        JavaType javaType = this.mapper.constructType(OrderedBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 数字较小的 second 在前，Schema 与消息写入共享此属性顺序。
        List<String> expected = new ArrayList<>();
        expected.add("second");
        expected.add("first");
        List<String> names = type.getProperties().stream() //
                .map(SoapXmlProperty::getJavaName) //
                .toList();
        assertEquals(expected, names);
        assertEquals(10, type.getProperties().get(0).getOrder());
        assertEquals(20, type.getProperties().get(1).getOrder());
    }

    @Test
    public void defaultOrderKeepsPropertyDiscoveryOrder() {
        // 未标注、默认 公共名称与 XML 配置 和显式 order=0 都沿用同一个默认顺序。
        JavaType javaType = this.mapper.constructType(DefaultOrderBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 同值保持 Jackson 原映射发现顺序，不改成字段名或声明位置排序。
        List<String> expected = new ArrayList<>();
        expected.add("third");
        expected.add("first");
        expected.add("second");
        List<String> names = type.getProperties().stream() //
                .map(SoapXmlProperty::getJavaName) //
                .toList();
        assertEquals(expected, names);
        assertTrue(type.getProperties().stream().allMatch(property -> property.getOrder() == 0));
    }

    @Test
    public void equalExplicitOrdersKeepPropertyDiscoveryOrder() {
        // 各元素都配置相同的非零数字，排序仍需稳定。
        JavaType javaType = this.mapper.constructType(EqualOrderBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 相同 order 不丢失原有 Jackson 映射顺序。
        List<String> expected = new ArrayList<>();
        expected.add("second");
        expected.add("first");
        expected.add("third");
        List<String> names = type.getProperties().stream() //
                .map(SoapXmlProperty::getJavaName) //
                .toList();
        assertEquals(expected, names);
    }

    @Test
    public void negativeOrderPrecedesDefaultAndPositiveOrder() {
        // 负数可用于把属性放在默认顺序之前，正数排在其后。
        JavaType javaType = this.mapper.constructType(NegativeOrderBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // order 使用完整有符号整数顺序。
        List<String> expected = new ArrayList<>();
        expected.add("negativeValue");
        expected.add("defaultValue");
        expected.add("positiveValue");
        List<String> names = type.getProperties().stream() //
                .map(SoapXmlProperty::getJavaName) //
                .toList();
        assertEquals(expected, names);
    }

    @Test
    public void integerBoundaryOrdersDoNotOverflow() {
        // 最大值、最小值与默认值同时出现，比较不能通过整数相减实现。
        JavaType javaType = this.mapper.constructType(BoundaryOrderBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 完整 int 范围内仍按从小到大的顺序排列。
        List<String> expected = new ArrayList<>();
        expected.add("minimum");
        expected.add("zero");
        expected.add("maximum");
        List<String> names = type.getProperties().stream() //
                .map(SoapXmlProperty::getJavaName) //
                .toList();
        assertEquals(expected, names);
    }

    @Test
    public void attributeOrderUsesIncreasingValues() {
        // XML attribute 也能独立配置顺序，默认值为零。
        JavaType javaType = this.mapper.constructType(OrderedAttributeBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 编译后的属性列表与 XSD attribute 声明沿用数字升序。
        List<String> expected = new ArrayList<>();
        expected.add("second");
        expected.add("third");
        expected.add("first");
        List<String> names = type.getProperties().stream() //
                .filter(SoapXmlProperty::isAttribute) //
                .map(SoapXmlProperty::getJavaName) //
                .toList();
        assertEquals(expected, names);
    }

    @Test
    public void textPropertyUsesJacksonPropertyOrder() {
        // JacksonXmlText 没有顺序配置，与未标注属性一样使用零。
        JavaType javaType = this.mapper.constructType(OrderedTextBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 文本在负数与正数顺序的属性之间，仍保持 simpleContent 结构。
        List<String> expected = new ArrayList<>();
        expected.add("before");
        expected.add("text");
        expected.add("after");
        List<String> names = type.getProperties().stream() //
                .map(SoapXmlProperty::getJavaName) //
                .toList();
        assertEquals(expected, names);
        assertTrue(type.getProperties().get(1).isText());
        assertEquals(1, type.getProperties().get(1).getOrder());
    }

    @Test
    public void conflictingFieldAndGetterOrdersAreRejected() {
        // 同一 Java 属性的字段和 getter 不能声明不同的元素顺序。
        JavaType javaType = this.mapper.constructType(ConflictingElementOrderAccessors.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                this.mapping.type(javaType));
        assertTrue(failure.getMessage().contains("conflicting JsonProperty mappings"));
    }

    @Test
    public void conflictingFieldAndSetterAttributeOrdersAreRejected() {
        // XML attribute 的读写成员同样必须对顺序达成一致。
        JavaType javaType = this.mapper.constructType(ConflictingAttributeOrderAccessors.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                this.mapping.type(javaType));
        assertTrue(failure.getMessage().contains("conflicting JsonProperty mappings"));
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
    public void commonNameCanDescribeAnAttribute() {
        // 公共名称不指定 XML 节点类别，与 attribute 可以组合。
        JavaType javaType = this.mapper.constructType(ElementAttributeConflict.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        assertTrue(property.isAttribute());
        assertEquals("code", property.getName().getLocalPart());
    }

    @Test
    public void fieldAndGetterCannotDeclareDifferentXmlKinds() {
        // 字段和 getter 属于同一个 Java 属性，声明位置不同也不能改变节点角色。
        JavaType javaType = this.mapper.constructType(FieldGetterKindConflict.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                this.mapping.type(javaType));
        assertTrue(failure.getMessage().contains("conflicting JacksonXmlProperty mappings"));
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
    public void collectionWrapperAndItemShareNamespaceAndConstraints() {
        // 集合容器和条目保留各自名称，共用命名空间、必填及可空设置。
        JavaType javaType = this.mapper.constructType(WrappedCollectionBean.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        // 同一个注解声明同时约束容器和条目。
        assertEquals(new QName("urn:items", "messages"), property.getWrapper());
        assertEquals(new QName("urn:items", "message"), property.getName());
        assertTrue(property.isWrapperRequired());
        assertFalse(property.isWrapperNillable());
        assertTrue(property.isRequired());
        assertFalse(property.isNillable());
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
    public void primitiveArrayValueAndWrapperShareTheNonNillableConstraint() {
        // 集合包装层与条目使用同一个非空声明。
        JavaType javaType = this.mapper.constructType(WrappedPrimitiveArrayBean.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        // 包装层和条目都保留显式配置的非空约束。
        assertEquals(new QName("", "values"), property.getWrapper());
        assertFalse(property.isWrapperNillable());
        assertFalse(property.isNillable());
    }

    @Test
    public void jacksonWrapperRemainsWhenMeteorDoesNotDeclareAWrap() {
        // 元素命名与现有 Jackson 容器映射可以一起使用。
        JavaType javaType = this.mapper.constructType(JacksonWrappedCollectionBean.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        assertEquals(new QName("urn:legacy", "entries"), property.getWrapper());
        assertEquals(new QName("", "item"), property.getName());
    }

    @Test
    public void commonNamePreservesTheJacksonCollectionWrapper() {
        // 显式 wrap 配置覆盖旧容器，集合项仍使用自己的名称。
        JavaType javaType = this.mapper.constructType(MeteorWrappedCollectionBean.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        assertEquals(new QName("urn:legacy", "entries"), property.getWrapper());
        assertEquals(new QName("urn:meteor", "item"), property.getName());
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
    public void commonNamePreservesJsonIgnore() {
        // 公共名称只负责命名，不撤销 Jackson 的忽略指令。
        JavaType javaType = this.mapper.constructType(MeteorOverridesIgnoreBean.class);
        SoapXmlType type = this.mapping.type(javaType);

        // 被忽略字段仍不进入 XML 契约。
        assertEquals(1, type.getProperties().size());
        assertEquals(new QName("", "visible"), type.getProperties().get(0).getName());
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
    public void commonNamePreservesJsonReadOnlyAccess() {
        // 公共注解不能把只读属性变成可写属性。
        JavaType javaType = this.mapper.constructType(MeteorOverridesReadOnlyBean.class);
        SoapXmlProperty property = this.mapping.type(javaType).getProperties().get(0);

        assertTrue(property.isReadable());
        assertFalse(property.isWritable());
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
    public void emptyElementNameUsesTheJavaEnumConstantName() {
        // 枚举常量也可以只标注 公共名称与 XML 配置，空名称沿用 Java 常量名称。
        String value = this.mapping.enumValue(DefaultElementState.READY);

        assertEquals("READY", value);
        assertEquals(DefaultElementState.READY, this.mapping.enumValue(DefaultElementState.class, value));
    }

    @Test
    public void emptyEnumNamePreservesTheJacksonAlias() {
        // 没有公共名称声明时沿用 Jackson 的枚举名称。
        String value = this.mapping.enumValue(EmptyOverrideState.READY);

        assertEquals("jackson-ready", value);
        assertEquals(EmptyOverrideState.READY, this.mapping.enumValue(EmptyOverrideState.class, value));
    }

    @Test
    public void enumElementNameCanContainSpaces() {
        // 枚举的 name 表示 XML 文本，可以包含 XML 元素名称不允许的空格。
        JavaType javaType = this.mapper.constructType(TextState.class);
        this.mapping.type(javaType);
        String value = this.mapping.enumValue(TextState.READY);

        assertEquals("ready to send", value);
        assertEquals(TextState.READY, this.mapping.enumValue(TextState.class, value));
    }

    @Test
    public void duplicateEnumAliasesAreRejected() {
        // 两个枚举常量不能共享同一个 XML 字面量。
        JavaType javaType = this.mapper.constructType(DuplicateState.class);

        // 定义阶段拒绝不能确定反向映射的枚举。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(javaType));
    }

    @Test
    public void enumConstantAttributeHasNoParentElement() {
        // 枚举常量生成的是文本，不存在可承载此常量属性的 DTO 元素。
        JavaType javaType = this.mapper.constructType(AttributeState.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                this.mapping.type(javaType));
        assertTrue(failure.getMessage().contains("XML attribute has no parent element"));
        assertTrue(failure.getMessage().contains(AttributeState.class.getName() + "#READY"));
    }

    @Test
    public void unknownEnumLiteralIsRejected() {
        // 对端传入不在枚举契约内的值。
        String literal = "does-not-exist";

        // 不默认为某个业务状态。
        assertThrows(IllegalArgumentException.class, () -> this.mapping.enumValue(MeteorState.class, literal));
    }

    @Test
    public void jacksonIdentityUsesAnOpenValueMapping() {
        // 对象 ID 语义由 Jackson 负责，Schema 不把动态引用当成普通字段。
        JavaType javaType = this.mapper.constructType(IdentityBean.class);

        // 开放值交给 Jackson 编解码，不猜测其结构。
        assertTrue(this.mapping.type(javaType).isJacksonValue());
    }

    @Test
    public void customJsonValueEnumUsesAnOpenValueMapping() {
        // 自定义 JsonValue 可能把枚举输出为任意结构，不能冒充 XSD 枚举字面量。
        JavaType javaType = this.mapper.constructType(CustomValueState.class);

        // JsonValue 的输出由 Jackson 决定，不生成错误的固定枚举列表。
        assertTrue(this.mapping.type(javaType).isJacksonValue());
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
        @MetField(name = "text")
        @JacksonXmlProperty(namespace = "urn:message")
        @JsonProperty(index = 5)
        private String message;

        @MetField(name = "text")
        @JacksonXmlProperty(namespace = "urn:message")
        @JsonProperty(index = 5)
        public String getMessage() {
            return this.message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class ConflictingAccessors {
        @MetField(name = "field")
        private String message;

        @MetField(name = "getter")
        public String getMessage() {
            return this.message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class ConflictingSetter {
        @MetField(name = "field")
        private String message;

        public String getMessage() {
            return this.message;
        }

        @MetField(name = "setter")
        public void setMessage(String message) {
            this.message = message;
        }
    }

    public interface ConflictingInterface {
        @MetField(name = "left")
        String getMessage();

        @MetField(name = "right")
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
        @MetField(name = "text")
        @JacksonXmlProperty(namespace = "urn:interface")
        String getMessage();

        @MetField(name = "text")
        @JacksonXmlProperty(namespace = "urn:interface")
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
        @MetField(name = "message")
        public String message;
    }

    public static class MeteorOverridesJacksonJson {
        @JsonProperty("legacy")
        @MetField(name = "message")
        @JacksonXmlProperty(namespace = "urn:meteor")
        public String message;
    }

    public static class JacksonXmlBean {
        @JacksonXmlProperty(localName = "text", namespace = "urn:jackson")
        public String message;
    }

    @MetType(name = "MessageType")
    @JacksonXmlRootElement(namespace = "urn:types")
    public static class NamedBean {
        public String message;
    }

    public static class OrderedBean {
        @MetField(name = "a")
        @JsonProperty(index = 20)
        public String first;
        @MetField(name = "b")
        @JsonProperty(index = 10)
        public String second;
    }

    @JsonPropertyOrder({ "third", "first", "second" })
    public static class DefaultOrderBean {
        public String first;
        @MetField
        public String second;
        @MetField
        @JsonProperty(index = 0)
        public String third;
    }

    @JsonPropertyOrder({ "second", "first", "third" })
    public static class EqualOrderBean {
        @MetField
        @JsonProperty(index = 5)
        public String first;
        @MetField
        @JsonProperty(index = 5)
        public String second;
        @MetField
        @JsonProperty(index = 5)
        public String third;
    }

    public static class NegativeOrderBean {
        public String defaultValue;
        @MetField
        @JsonProperty(index = -10)
        public String negativeValue;
        @MetField
        @JsonProperty(index = 10)
        public String positiveValue;
    }

    public static class BoundaryOrderBean {
        @MetField
        @JsonProperty(index = Integer.MAX_VALUE)
        public String maximum;
        @MetField
        @JsonProperty(index = Integer.MIN_VALUE)
        public String minimum;
        public String zero;
    }

    public static class OrderedAttributeBean {
        public String message;
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = 20)
        public String first;
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = -10)
        public String second;
        @JacksonXmlProperty(isAttribute = true)
        public String third;
    }

    public static class OrderedTextBean {
        @JacksonXmlText
        @JsonProperty(index = 1)
        public String text;
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = 0)
        public String before;
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = 2)
        public String after;
    }

    public static class ConflictingElementOrderAccessors {
        @MetField
        @JsonProperty(index = 1)
        private String message;

        @MetField
        @JsonProperty(index = 2)
        public String getMessage() {
            return this.message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class ConflictingAttributeOrderAccessors {
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = 1)
        private String message;

        public String getMessage() {
            return this.message;
        }

        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = 2)
        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class GenericCollectionBean {
        public List<Long> values;
    }

    public static class PrivateFieldBean {
        @MetField(name = "text")
        private String message;
    }

    public static class RequiredElementBean {
        @MetField
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        public String message;
    }

    public static class AttributeBean {
        @MetField(name = "code")
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:attributes")
        @JsonProperty(required = true)
        public String code;
    }

    public static class ElementAttributeConflict {
        @MetField(name = "code")
        @JacksonXmlProperty(isAttribute = true)
        public String value;
    }

    public static class FieldGetterKindConflict {
        @MetField
        @JacksonXmlProperty(isAttribute = false)
        private String value;

        @JacksonXmlProperty(isAttribute = true)
        public String getValue() {
            return this.value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    public static class NamespaceDeclarationBean {
        @MetField(name = "xmlns")
        @JacksonXmlProperty(isAttribute = true)
        public String namespace;
    }

    public static class ReservedNamespaceBean {
        @MetField
        @JacksonXmlProperty(namespace = XMLConstants.XMLNS_ATTRIBUTE_NS_URI)
        public String message;
    }

    public static class TextWithAttribute {
        @JacksonXmlText
        public String text;
        @JacksonXmlProperty(isAttribute = true)
        public String language;
    }

    public static class MixedContentBean {
        @JacksonXmlText
        public String text;
        public String message;
    }

    public static class DuplicateTextBean {
        @JacksonXmlText
        public String first;
        @JacksonXmlText
        public String second;
    }

    public static class WrappedCollectionBean {
        @MetField(name = "message")
        @JacksonXmlProperty(namespace = "urn:items")
        @JacksonXmlElementWrapper(localName = "messages", namespace = "urn:items")
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        public List<String> messages;
    }

    public static class PrimitiveArrayBean {
        public int[] values;
    }

    public static class WrappedPrimitiveArrayBean {
        @MetField(name = "value")
        @JacksonXmlElementWrapper(localName = "values")
        @JsonSetter(nulls = Nulls.FAIL)
        public int[] values;
    }

    public static class JacksonWrappedCollectionBean {
        @JacksonXmlElementWrapper(localName = "entries", namespace = "urn:legacy")
        @MetField(name = "item")
        public List<String> values;
    }

    public static class MeteorWrappedCollectionBean {
        @JacksonXmlElementWrapper(localName = "entries", namespace = "urn:legacy")
        @MetField(name = "item")
        @JacksonXmlProperty(namespace = "urn:meteor")
        public List<String> values;
    }

    public static class ComplexAttributeBean {
        @JacksonXmlProperty(isAttribute = true)
        public PlainBean value;
    }

    public static class NamespacedSiblings {
        @MetField(name = "value")
        @JacksonXmlProperty(namespace = "urn:first")
        public String first;
        @MetField(name = "value")
        @JacksonXmlProperty(namespace = "urn:second")
        public String second;
    }

    public static class DuplicateElementBean {
        @MetField(name = "value")
        public String first;
        @MetField(name = "value")
        public String second;
    }

    public static class ElementAndAttributeBean {
        @MetField(name = "value")
        public String element;
        @MetField(name = "value")
        @JacksonXmlProperty(isAttribute = true)
        public String attribute;
    }

    public static class TransientBean {
        public String message;
        @JsonIgnore
        public String localOnly;
    }

    public static class JacksonIgnoreBean {
        public String message;
        @JsonIgnore
        public String localOnly;
    }

    public static class MeteorOverridesIgnoreBean {
        public String visible;
        @JsonIgnore
        @MetField
        public String message;
    }

    public static class TransientElementBean {
        public String message;
        @JsonIgnore
        @MetField
        public String localOnly;
    }

    public static class MeteorOverridesReadOnlyBean {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @MetField
        public String message;
    }

    public record GetterOnlyPartBean(String state) {

        @Override
        @MetField(name = "status")
        @JacksonXmlProperty(namespace = "urn:parts")
        public String state() {
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
        @MetEnumValue(name = "ready-for-use") READY
    }

    public enum PlainState {
        READY
    }

    public enum AttributeState {
        @JacksonXmlProperty(isAttribute = true) READY
    }

    public enum JacksonState {
        @JsonProperty("jackson-ready") READY
    }

    public enum OverrideState {
        @JsonProperty("jackson-ready") @MetEnumValue(name = "meteor-ready") READY
    }

    public enum DefaultElementState {
        @MetEnumValue READY
    }

    public enum EmptyOverrideState {
        @JsonProperty("jackson-ready") @MetEnumValue(name = "") READY
    }

    public enum TextState {
        @MetEnumValue(name = "ready to send") READY
    }

    public enum DuplicateState {
        @MetEnumValue(name = "same") FIRST,
        @MetEnumValue(name = "same") SECOND
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
