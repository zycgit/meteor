/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.meta;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.annotation.*;
import net.hasor.meteor.domain.ServiceDomain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ServiceMetaParserTest {
    interface Base<T> {
        T echo(@MetParam(name = "request", id = 7) T value);
    }

    @MetService(name = "Orders", group = "shop", version = "2")
    interface Orders extends Base<Order> {
        @MetMethod(name = "Watch")
        @MetReturn(name = "events")
        Flow.Publisher<Order> watch();

        @MetIgnore
        void hidden();

        @MetMessage
        void notify(String value);
    }

    @MetService(id = 0xffff_ffffL, name = "Numbered", group = "contracts", version = "2026.10-rc1")
    interface NumberedParent {
        @MetMethod(id = 0xffff_fffeL)
        String invoke();
    }

    @MetService(name = "Child")
    interface InheritedNumber extends NumberedParent {
    }

    @MetService(id = 0)
    interface ZeroNumber extends NumberedParent {
    }

    @MetService(id = 7)
    interface OtherNumber {
    }

    interface ConflictingNumbers extends NumberedParent, OtherNumber {
    }

    @MetService(id = 23)
    interface OverriddenNumbers extends NumberedParent, OtherNumber {
    }

    @MetService(id = 0xffff_ffffL)
    interface MatchingNumber {
    }

    interface MatchingNumbers extends NumberedParent, MatchingNumber {
    }

    interface BlankMethodName {
        @MetMethod(id = 1, name = " \t")
        void invalid();
    }

    @MetType(name = "Purchase")
    static class Order {
        @MetField(id = 7)
        private String id;

        @MetField(name = "order_id")
        public String getId() {
            return this.id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Order next;
    }

    static class Page<T> {
        public List<T> values;
    }

    enum Status {
        @MetEnumValue(name = "READY", id = 0) Ready,
        Unknown
    }

    interface Generic<T> {
        <T> T identity(T value);

        <R extends T> R bounded(R value);
    }

    interface Concrete extends Generic<Order> {
    }

    static class Mixed {
        public String value;
    }

    static class FirstMixin {
        @MetField(name = "first", id = 1)
        public String value;
    }

    static class SecondMixin {
        @MetField(name = "second", id = 2)
        public String value;
    }

    static class NotConstructed {
        public String value;

        public NotConstructed() {
            throw new AssertionError("DTO must not be constructed during description");
        }
    }

    static class ReadOnly {
        @MetField(id = 1)
        public String getValue() {
            return "read only";
        }
    }

    static class Conflict {
        @MetField(id = 1)
        public String item;

        @MetField(id = 2)
        public String getItem() {
            return this.item;
        }
    }

    @Test
    public void serviceIdentityComesFromTheCommonDeclaration() {
        // 解析接口声明，不创建业务实现。
        ServiceMeta service = ServiceMetaParser.parse(Orders.class);
        assertEquals("Orders", service.getName());
        assertEquals("shop", service.getGroup());
        assertEquals("2", service.getVersion());
    }

    @Test
    public void missingServiceAnnotationKeepsDeclarationValuesUnspecified() {
        // 没有服务注解时仅描述 Java 接口，不填入某个容器的配置默认值。
        ServiceMeta service = ServiceMetaParser.parse(Base.class);
        assertEquals(Base.class, service.getJavaType());
        assertEquals("", service.getName());
        assertEquals("", service.getGroup());
        assertEquals("", service.getVersion());
        assertEquals("", service.getSerializeType());
        assertEquals(-1, service.getClientTimeout());
        assertEquals(-1L, service.getContractServiceId());
    }

    @Test
    public void partialServiceAnnotationKeepsCallOptionsUnspecified() {
        // 身份声明不会补齐未指定的超时或编码器，最终值由注册所属容器决定。
        ServiceMeta service = ServiceMetaParser.parse(Orders.class);
        assertEquals("", service.getSerializeType());
        assertEquals(-1, service.getClientTimeout());
    }

    @Test
    public void partialServiceAnnotationKeepsContractNumberUnspecified() {
        // 原有名称、分组和版本声明不意味着分配了服务契约编号。
        ServiceMeta service = ServiceMetaParser.parse(Orders.class);
        assertEquals(-1L, service.getContractServiceId());
    }

    @Test
    public void serviceContractNumberRetainsTheUnsignedHighHalf() {
        // 使用 uint32 的高半区，公共描述以 long 保留完整编号。
        ServiceMeta service = ServiceMetaParser.parse(NumberedParent.class);
        assertEquals(0xffff_ffffL, service.getContractServiceId());
    }

    @Test
    public void numericServiceIdentityKeepsTheStringVersionUnchanged() {
        // 服务契约编号与原有字符串版本是两个独立声明。
        ServiceMeta service = ServiceMetaParser.parse(NumberedParent.class);
        assertEquals("2026.10-rc1", service.getVersion());
    }

    @Test
    public void unspecifiedServiceNumberInheritsWithoutCopyingOtherIdentityFields() {
        // 子接口只声明名称，编号继承父接口，其余服务身份仍遵循当前声明。
        ServiceMeta service = ServiceMetaParser.parse(InheritedNumber.class);
        assertEquals(0xffff_ffffL, service.getContractServiceId());
        assertEquals("Child", service.getName());
        assertEquals("", service.getGroup());
        assertEquals("", service.getVersion());
    }

    @Test
    public void explicitZeroServiceNumberOverridesTheParent() {
        // 零是合法的服务编号，不能当作缺省值而回退到父接口。
        ServiceMeta service = ServiceMetaParser.parse(ZeroNumber.class);
        assertEquals(0L, service.getContractServiceId());
    }

    @Test
    public void equalServiceNumbersFromMultipleParentsRemainUnambiguous() {
        // 多个父接口声明相同编号时，继承结果仍是一份确定的服务编号。
        ServiceMeta service = ServiceMetaParser.parse(MatchingNumbers.class);
        assertEquals(0xffff_ffffL, service.getContractServiceId());
    }

    @Test
    public void differentServiceNumbersFromMultipleParentsFailWithoutAnOverride() {
        // 未明确覆盖多个父接口的不同编号时，报告公共声明冲突。
        assertThrows(IllegalArgumentException.class, () -> ServiceMetaParser.parse(ConflictingNumbers.class));
    }

    @Test
    public void explicitServiceNumberResolvesConflictingParentNumbers() {
        // 当前接口明确给出的编号优先，不受父接口编号冲突影响。
        ServiceMeta service = ServiceMetaParser.parse(OverriddenNumbers.class);
        assertEquals(23L, service.getContractServiceId());
    }

    @Test
    public void missingMethodAnnotationKeepsTheContractNumberUnspecified() throws Exception {
        // 未声明方法注解时，契约编号保持缺省，名称仍来自 Java 方法。
        Method method = Base.class.getMethod("echo", Object.class);
        MethodMeta description = ServiceMetaParser.method(method);
        assertEquals(0L, description.getMethodId());
        assertNull(description.getDeclaredName());
        assertEquals("echo", description.getName());
    }

    @Test
    public void methodContractNumberDoesNotRequireAnExplicitName() throws Exception {
        // 仅指定编号，不能把注解缺省的空名称误判为显式无效名称。
        Method method = NumberedParent.class.getMethod("invoke");
        MethodMeta description = ServiceMetaParser.method(method);
        assertEquals(0xffff_fffeL, description.getMethodId());
        assertNull(description.getDeclaredName());
        assertEquals("invoke", description.getName());
    }

    @Test
    public void explicitlyBlankMethodNameStillFails() throws Exception {
        // 空白字符串是显式名称错误，与未声明名称的空字符串区别处理。
        Method method = BlankMethodName.class.getMethod("invalid");
        assertThrows(IllegalArgumentException.class, () -> ServiceMetaParser.method(method).getName());
    }

    @Test
    public void inheritedMethodResolvesTheConcreteServiceType() throws Exception {
        // 服务根类型决定继承方法中 T 的实际类型。
        Method method = Base.class.getMethod("echo", Object.class);
        MethodMeta description = ServiceMetaParser.parse(Orders.class).getMethod(method);
        assertEquals(Order.class, description.getReturnType().resolve());
        assertEquals(Order.class, description.getParameters().get(0).getType().resolve());
    }

    @Test
    public void parameterPositionAndProtocolIdStayIndependent() throws Exception {
        // field id 为 7 的参数仍然位于 Java 参数数组的第零项。
        Method method = Base.class.getMethod("echo", Object.class);
        ParameterMeta parameter = ServiceMetaParser.method(Orders.class, method).getParameters().get(0);
        assertEquals(0, parameter.getArgIndex());
        assertEquals(7, parameter.getFieldId());
        assertEquals("request", parameter.getDeclaredName());
        assertEquals("request", parameter.getName());
    }

    @Test
    public void returnMappingKeepsThePublisherAndItsElementType() throws Exception {
        // 返回值映射只命名协议输出，完整流类型及元素类型分别可查。
        MethodMeta method = ServiceMetaParser.method(Orders.class.getMethod("watch"));
        assertEquals("Watch", method.getDeclaredName());
        assertEquals("Watch", method.getName());
        assertEquals("watch", method.getJavaName());
        assertEquals("events", method.getReturnName());
        assertEquals(Flow.Publisher.class, method.getReturnType().resolve());
        assertEquals(Order.class, method.getResponseType().resolve());
    }

    @Test
    public void ignoredMethodsDoNotEnterTheServiceMethodList() {
        // 方法描述仍可解释注解，公开服务列表排除被忽略的方法。
        ServiceMeta service = ServiceMetaParser.parse(Orders.class);
        assertFalse(service.getMethods().stream().anyMatch(method -> method.isIgnored()));
        assertEquals(3, service.getMethods().size());
    }

    @Test
    public void methodMessageModeIsDescribedWithoutExecutingIt() throws Exception {
        // 消息模式来自原始方法声明，不引入协议回执规则。
        MethodMeta method = ServiceMetaParser.method(Orders.class.getMethod("notify", String.class));
        assertTrue(method.isMessage());
        assertFalse(ServiceMetaParser.parse(Orders.class).isMessage());
    }

    @Test
    public void fieldAndGetterSupplyDifferentPartsOfTheSameDeclaration() {
        // 编号和名称在一个 Java 属性上合并。
        PropertyMeta property = ServiceMetaParser.type(Order.class).getProperties().get("id");
        assertEquals(7, property.getFieldId());
        assertEquals("order_id", property.getDeclaredName());
        assertEquals("order_id", property.getName());
        assertNotNull(property.getField());
        assertNotNull(property.getGetter());
        assertNotNull(property.getSetter());
    }

    @Test
    public void genericDtoDescriptionsKeepTheirActualArguments() {
        // 同一 DTO 的不同泛型实参分别描述。
        ServiceMeta service = ServiceMetaParser.parse(Orders.class);
        ResolvableType orders = ResolvableType.forClassWithGenerics(Page.class, Order.class);
        ResolvableType strings = ResolvableType.forClassWithGenerics(Page.class, String.class);
        TypeMeta orderPage = service.getType(orders);
        TypeMeta stringPage = service.getType(strings);
        assertEquals(Order.class, orderPage.getProperties().get("values").getType().getGeneric(0).resolve());
        assertEquals(String.class, stringPage.getProperties().get("values").getType().getGeneric(0).resolve());
        assertSame(orderPage, service.getType(orders));
    }

    @Test
    public void recursiveDtoKeepsATypeReference() {
        // 递归属性只保存其声明类型，不递归构造无限元信息树。
        PropertyMeta next = ServiceMetaParser.type(Order.class).getProperties().get("next");
        assertEquals(Order.class, next.getType().resolve());
    }

    @Test
    public void enumZeroIsDifferentFromAMissingDeclaration() {
        // 显式零是有效枚举值，缺少注解的常量仍保留未声明状态。
        EnumValueMeta ready = ServiceMetaParser.enumValue(Status.Ready);
        EnumValueMeta unknown = ServiceMetaParser.enumValue(Status.Unknown);
        assertTrue(ready.isDeclared());
        assertEquals(0, ready.getFieldId());
        assertEquals("READY", ready.getDeclaredName());
        assertEquals("READY", ready.getName());
        assertEquals("", unknown.getDeclaredName());
        assertFalse(unknown.isDeclared());
    }

    @Test
    public void typeMappingKeepsTheRawNameSeparateFromTheJavaType() {
        // 类型名称字段保存原始公共声明，Java 定位保持实际 DTO 类型。
        TypeMeta type = ServiceMetaParser.type(Order.class);
        assertEquals("Purchase", type.getDeclaredName());
        assertEquals("Purchase", type.getName());
        assertEquals(Order.class, type.getRawType());
    }

    @Test
    public void unannotatedTypeKeepsItsRawNameAbsent() {
        // 未声明名称时只在有效名称查询中回退，原始名称仍为 null。
        TypeMeta type = ServiceMetaParser.type(Mixed.class);
        assertNull(type.getDeclaredName());
        assertEquals("Mixed", type.getName());
        assertFalse(type.isDeclared());
    }

    @Test
    public void conflictingPropertyDeclarationsFailInTheCommonParser() {
        // 对同一属性声明不同编号，在转换为协议之前报告冲突。
        assertThrows(IllegalArgumentException.class, () -> ServiceMetaParser.type(Conflict.class).getProperties());
    }

    @Test
    public void protocolGroupsReuseTheServiceDescription() {
        // 资源由协议分组持有，公共描述不包含某个协议的绑定资源。
        ServiceDomain<Orders> service = new ServiceDomain<>(Orders.class);
        service.setBindResource("grpc", new Object());
        service.setBindResource("soap", new Object());
        ProtocolMeta grpc = service.getProtocolMeta("grpc");
        ProtocolMeta soap = service.getProtocolMeta("soap");
        assertSame(service.getServiceMeta(), grpc.getServiceMeta());
        assertSame(grpc.getServiceMeta(), soap.getServiceMeta());
    }

    @Test
    public void methodTypeVariableShadowsTheSameNamedServiceVariable() throws Exception {
        // 方法自己的 T 没有具体实参，不能借用接口 T 已绑定的 Order。
        Method method = Generic.class.getMethod("identity", Object.class);
        MethodMeta description = ServiceMetaParser.method(Concrete.class, method);
        assertEquals(Object.class, description.getReturnType().resolve());
        assertEquals(Object.class, description.getParameters().get(0).getType().resolve());
    }

    @Test
    public void methodTypeVariableCanUseTheServiceVariableAsItsBound() throws Exception {
        // R 的上界引用接口 T，接口根类型仍能将它解析为 Order。
        Method method = Generic.class.getMethod("bounded", Object.class);
        MethodMeta description = ServiceMetaParser.method(Concrete.class, method);
        assertEquals(Order.class, description.getReturnType().resolve());
    }

    @Test
    public void mapperMixinsProduceIndependentCommonPropertyViews() {
        // 两个 mapper 对同一个 DTO 提供不同声明，公共类描述不缓存端点的 mixin 结果。
        ObjectMapper first = new ObjectMapper().addMixIn(Mixed.class, FirstMixin.class);
        ObjectMapper second = new ObjectMapper().addMixIn(Mixed.class, SecondMixin.class);
        JavaType type = first.constructType(Mixed.class);
        var firstBean = first.getSerializationConfig().introspect(type);
        var secondBean = second.getSerializationConfig().introspect(type);
        BeanPropertyDefinition firstProperty = firstBean.findProperties().get(0);
        BeanPropertyDefinition secondProperty = secondBean.findProperties().get(0);

        // 有效成员交给相同解析入口，名称、编号保持所属 mapper 的配置。
        PropertyMeta firstView = ServiceMetaParser.property(firstProperty);
        PropertyMeta secondView = ServiceMetaParser.property(secondProperty);
        assertEquals("first", firstView.getName());
        assertEquals(1, firstView.getFieldId());
        assertEquals("second", secondView.getName());
        assertEquals(2, secondView.getFieldId());
        assertFalse(ServiceMetaParser.type(Mixed.class).getProperties().get("value").isDeclared());
    }

    @Test
    public void dtoDescriptionDoesNotInstantiateTheApplicationType() {
        // 构造方法会失败，描述阶段仍然只发现成员并取得声明类型。
        TypeMeta description = ServiceMetaParser.type(NotConstructed.class);
        assertEquals(String.class, description.getProperties().get("value").getType().resolve());
    }

    @Test
    public void readOnlyPropertiesRemainAvailableForProtocolSpecificBinding() {
        // 公共描述保留只读属性，由具体协议结合自己的构造与解码能力判断是否可用。
        PropertyMeta property = ServiceMetaParser.type(ReadOnly.class).getProperties().get("value");
        assertNotNull(property.getGetter());
        assertNull(property.getSetter());
        assertEquals(1, property.getFieldId());
    }

}
