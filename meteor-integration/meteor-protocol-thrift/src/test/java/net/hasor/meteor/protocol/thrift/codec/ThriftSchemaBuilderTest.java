/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import net.hasor.meteor.annotation.MetEnumValue;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.annotation.MetMethod;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.example.protocol.thrift.mapping.Note;
import net.hasor.example.protocol.thrift.mapping.MappedBean;
import net.hasor.example.protocol.thrift.mapping.MappedState;
import net.hasor.example.protocol.thrift.mapping.FailingNote;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.thrift.ThriftIdlResource;
import net.hasor.meteor.protocol.thrift.annotation.*;
import org.apache.thrift.protocol.TType;
import org.junit.Test;
import static org.junit.Assert.*;

/** 发布阶段的注解契约验证，不构造任何业务 DTO 或调用实现。 */
public class ThriftSchemaBuilderTest {
    @Test
    public void fieldAndGetterMaySupplyDifferentPartsOfOneDeclaration() throws Exception {
        // 名称在字段上、编号在 getter 上，发布前合并成一个属性。
        ThriftType type = this.type(PartialDeclaration.class);
        ThriftFieldBinding field = type.fieldsById.get((short) 7);
        PartialDeclaration dto = new PartialDeclaration();
        field.set(dto, "merged");

        assertEquals("remote_text", field.field.name);
        assertEquals("merged", field.get(dto));
    }

    @Test
    public void conflictingFieldAndGetterNumbersAreRejected() {
        // 相同 Java 属性不能声明两个不同的线上编号。
        assertThrows(IllegalArgumentException.class, () -> this.type(ConflictingDeclaration.class));
    }

    @Test
    public void recursiveDtoUsesOneCompiledDefinition() {
        // Note.children 的 map/list 再次引用 Note。
        ThriftType type = this.type(Note.class);

        // 递归回边复用已有定义，不在启动时无限展开。
        ThriftType children = type.fieldsById.get((short) 4).type;
        assertSame(type, children.element.element);
    }

    @Test
    public void annotatedBeanGetterFindsItsSetter() throws Exception {
        // getter 注解与私有存储字段之间通过 Java Bean 属性关联。
        ThriftType type = this.type(MappedBean.class);
        MappedBean bean = new MappedBean();
        ThriftFieldBinding field = type.fieldsById.get((short) 1);

        // 编译结果可读写同一属性。
        field.set(bean, "setter");
        assertEquals("setter", field.get(bean));
    }

    @Test
    public void inheritedGenericFieldResolvesTheConcreteType() {
        // 子 DTO 将父类的 T 绑定到 String。
        ThriftType type = this.type(StringValue.class);

        // 不把继承字段退化成 Object 或 struct。
        assertSame(String.class, type.fieldsById.get((short) 1).type.javaType);
    }

    @Test
    public void parameterizedBeanGetterResolvesItsOwnerType() {
        // Bean<T> 作为另一个 DTO 的 Bean<String> 属性出现。
        ThriftType wrapper = this.type(BeanHolder.class);
        ThriftType bean = wrapper.fieldsById.get((short) 1).type;

        // getter 的 T 使用当前 DTO 泛型上下文解析。
        assertSame(String.class, bean.fieldsById.get((short) 1).type.javaType);
    }

    @Test
    public void inheritedServiceGenericParameterResolvesAtPublication() throws Exception {
        // 业务接口将父方法的参数和返回值绑定到 Note。
        ThriftServiceSchema schema = ThriftServiceSchema.annotated(new ServiceDomain<>(Notes.class));
        ThriftMethodBinding method = schema.javaMethod("echo");

        // 泛型声明在发布时编译，运行时不再解析 TypeVariable。
        assertSame(Note.class, method.args.fields.get(0).type.javaType);
        assertSame(Note.class, method.result.fieldsById.get((short) 0).type.javaType);
    }

    @Test
    public void enumNumbersDoNotFollowJavaDeclarationOrder() {
        // 第二个 Java 常量携带较小的协议编号。
        ThriftType type = this.type(MappedState.class);

        // 读取与写出均使用显式值。
        assertSame(MappedState.FIRST, type.enumValues.get(7));
        assertEquals(Integer.valueOf(7), type.enumNumbers.get(MappedState.FIRST));
    }

    @Test
    public void annotationCompilationDoesNotInstantiateDtos() {
        // DTO 构造方法抛错，但静态字段定义完整。
        ThriftType type = this.type(FailingNote.class);

        // 只缓存构造入口，不提前创建对象。
        assertEquals(TType.STRUCT, type.wireType);
    }

    @Test
    public void rawCollectionIsRejected() {
        // 没有元素类型的 List 无法形成跨语言契约。
        assertThrows(IllegalArgumentException.class, () -> this.type(RawList.class));
    }

    @Test
    public void wildcardCollectionIsRejected() {
        // 通配边界不是唯一的实例类型。
        assertThrows(IllegalArgumentException.class, () -> this.type(WildcardList.class));
    }

    @Test
    public void sortedCollectionWithoutADeclaredOrderingIsRejected() {
        // 协议集合不隐含 Java 比较器，不能返回 LinkedHashSet 冒充 SortedSet。
        assertThrows(IllegalArgumentException.class, () -> this.type(SortedValues.class));
    }

    @Test
    public void sortedMapWithoutADeclaredOrderingIsRejected() {
        // 不为自定义 map 实现猜测实例化策略。
        assertThrows(IllegalArgumentException.class, () -> this.type(SortedEntries.class));
    }

    @Test
    public void objectFieldIsRejected() {
        // Object 不能在标准 Thrift 中携带任意 Java 类身份。
        assertThrows(IllegalArgumentException.class, () -> this.type(OpenValue.class));
    }

    @Test
    public void duplicateFieldIdIsRejected() {
        // 一个 struct 中两个 Java 字段不能占用同一编号。
        assertThrows(IllegalArgumentException.class, () -> this.type(DuplicateId.class));
    }

    @Test
    public void duplicateFieldNameIsRejected() {
        // 名称不同的属性不能声明相同的 IDL 字段名。
        assertThrows(IllegalArgumentException.class, () -> this.type(DuplicateName.class));
    }

    @Test
    public void nonpositiveFieldIdIsRejected() {
        // 成功结果的 0 由协议保留，不能用于用户 DTO 字段。
        assertThrows(IllegalArgumentException.class, () -> this.type(ZeroId.class));
    }

    @Test
    public void optionalPrimitiveIsRejected() throws Exception {
        // primitive 不能区分 absent 和 present zero。
        ThriftIdlResource resource = this.resource("OptionalPrimitiveService");
        ServiceDomain<OptionalPrimitiveService> service = new ServiceDomain<>(OptionalPrimitiveService.class);
        assertThrows(IllegalArgumentException.class, () -> resource.bind(service));
    }

    @Test
    public void unionPrimitiveIsRejected() {
        // union 的未选择分支必须能保持 null。
        assertThrows(IllegalArgumentException.class, () -> this.type(PrimitiveUnion.class));
    }

    @Test
    public void requiredUnionMemberIsRejected() throws Exception {
        // 必填分支与 union 只能选择一个分支的语义冲突。
        ThriftIdlResource resource = this.resource("RequiredUnionService");
        ServiceDomain<RequiredUnionService> service = new ServiceDomain<>(RequiredUnionService.class);
        assertThrows(IllegalArgumentException.class, () -> resource.bind(service));
    }

    @Test
    public void finalFieldIsRejected() {
        // 不绕过 Java final 写入限制反序列化 DTO。
        assertThrows(IllegalArgumentException.class, () -> this.type(FinalField.class));
    }

    @Test
    public void getterWithoutSetterIsRejected() {
        // 只读 Bean 属性无法接收返回报文。
        assertThrows(IllegalArgumentException.class, () -> this.type(ReadOnlyBean.class));
    }

    @Test
    public void matchingFieldAndGetterDeclarationsMergeOneProperty() {
        // 字段和 getter 的同名同号声明合并，不能在契约中重复增加字段。
        ThriftType type = this.type(DualMapping.class);

        assertEquals(1, type.fields.size());
        assertEquals("value", type.fields.get(0).field.name);
    }

    @Test
    public void dtoWithoutNoArgConstructorIsRejected() {
        // 插件不猜测带参数构造方法的参数含义。
        assertThrows(IllegalArgumentException.class, () -> this.type(NoDefaultConstructor.class));
    }

    @Test
    public void abstractDtoCannotBeMaterialized() {
        // 不引入按报文类名选择 Java 实现的多态机制。
        assertThrows(IllegalArgumentException.class, () -> this.type(AbstractValue.class));
    }

    @Test
    public void enumWithoutExplicitNumbersIsRejected() {
        // Java ordinal 不作为默认协议契约。
        assertThrows(IllegalArgumentException.class, () -> this.type(UnmappedEnum.class));
    }

    @Test
    public void duplicateEnumNumberIsRejected() {
        // 一个协议值必须能还原为唯一 Java 常量。
        assertThrows(IllegalArgumentException.class, () -> this.type(DuplicateEnum.class));
    }

    @Test
    public void missingParameterIdIsRejected() {
        // 方法位置不能替代字段编号。
        ServiceDomain<MissingParameter> service = new ServiceDomain<>(MissingParameter.class);
        assertThrows(IllegalArgumentException.class, () -> ThriftServiceSchema.annotated(service));
    }

    @Test
    public void renamedWireMethodsMustBeUnique() {
        // 不同 Java 方法不能抢占同一个协议方法名。
        ServiceDomain<DuplicateMethod> service = new ServiceDomain<>(DuplicateMethod.class);
        assertThrows(IllegalArgumentException.class, () -> ThriftServiceSchema.annotated(service));
    }

    @Test
    public void messageCannotReturnAValue() {
        // MetMessage 对应标准 oneway，不能同时声明业务返回值。
        ServiceDomain<ReturningMessage> service = new ServiceDomain<>(ReturningMessage.class);
        assertThrows(IllegalArgumentException.class, () -> ThriftServiceSchema.annotated(service));
    }

    @Test
    public void throwsMappingMustAppearInTheJavaContract() throws Exception {
        // 未在接口 throws 中声明的异常不生成声明异常字段。
        ServiceDomain<UndeclaredFailure> service = new ServiceDomain<>(UndeclaredFailure.class);
        ThriftIdlResource resource = this.resource("UndeclaredFailureService");
        assertThrows(IllegalArgumentException.class, () -> resource.bind(service));
    }

    @Test
    public void methodAliasMustBeAnIdlIdentifier() {
        // 冒号属于 multiplexed 的服务分隔符，不能混入方法名。
        ServiceDomain<InvalidMethod> service = new ServiceDomain<>(InvalidMethod.class);
        assertThrows(IllegalArgumentException.class, () -> ThriftServiceSchema.annotated(service));
    }

    @Test
    public void onewayCannotDeclareAProtocolException() {
        // 没有业务响应的消息不能声明异常结果字段。
        ServiceDomain<FailingMessage> service = new ServiceDomain<>(FailingMessage.class);
        assertThrows(IllegalArgumentException.class, () -> ThriftServiceSchema.annotated(service));
    }

    private ThriftType type(Class<?> type) {
        return new ThriftSchemaBuilder().javaType(ResolvableType.forClass(type));
    }

    private ThriftIdlResource resource(String name) throws Exception {
        return new ThriftIdlResource(this.getClass().getResource("/thrift/mapping.thrift")).selectService(name);
    }

    public static class GenericValue<T> { @MetField(id = 1) private T value; }
    public static class StringValue extends GenericValue<String> {}
    public static class GenericBean<T> {
        private T value;
        @MetField(id = 1) public T getValue() { return this.value; }
        public void setValue(T value) { this.value = value; }
    }
    public static class BeanHolder { @MetField(id = 1) private GenericBean<String> bean; }
    public interface GenericService<T> { T echo(@MetParam(id = 1) T value); }
    public interface Notes extends GenericService<Note> {}
    public static class RawList { @MetField(id = 1) private List values; }
    public static class WildcardList { @MetField(id = 1) private List<? extends Number> values; }
    public static class SortedValues { @MetField(id = 1) private SortedSet<String> values; }
    public static class SortedEntries { @MetField(id = 1) private SortedMap<String, String> values; }
    public static class OpenValue { @MetField(id = 1) private Object value; }
    public static class DuplicateId {
        @MetField(id = 1) private int first;
        @MetField(id = 1) private int second;
    }
    public static class DuplicateName {
        @MetField(id = 1, name = "same") private int first;
        @MetField(id = 2, name = "same") private int second;
    }
    public static class ZeroId { @MetField(id = 0) private int value; }
    public static class OptionalPrimitive {
        @MetField(id = 1) private int value;
    }
    public interface OptionalPrimitiveService {
        void accept(@MetParam(id = 1) OptionalPrimitive value);
    }
    @ThriftUnion
    public static class PrimitiveUnion { @MetField(id = 1) private int value; }
    @ThriftUnion
    public static class RequiredUnion {
        @MetField(id = 1) private Integer value;
    }
    public interface RequiredUnionService {
        void accept(@MetParam(id = 1) RequiredUnion value);
    }
    public static class FinalField { @MetField(id = 1) private final int value = 1; }
    public static class ReadOnlyBean {
        @MetField(id = 1) public String getValue() { return "read-only"; }
    }
    public static class DualMapping {
        @MetField(id = 1) private String value;
        @MetField(id = 1) public String getValue() { return this.value; }
        public void setValue(String value) { this.value = value; }
    }
    public static class NoDefaultConstructor {
        @MetField(id = 1) private String value;
        public NoDefaultConstructor(String value) { this.value = value; }
    }
    public abstract static class AbstractValue { @MetField(id = 1) private int value; }
    public enum UnmappedEnum { FIRST }
    public enum DuplicateEnum { @MetEnumValue(id = 1) FIRST, @MetEnumValue(id = 1) SECOND }
    public interface MissingParameter { String echo(String value); }
    public interface DuplicateMethod {
        @MetMethod(name = "same") String first(@MetParam(id = 1) String value);
        @MetMethod(name = "same") String second(@MetParam(id = 1) String value);
    }
    public interface ReturningMessage { @MetMessage  String notifyCaller(); }
    public interface UndeclaredFailure {
         void fail();
    }
    public interface InvalidMethod { @MetMethod(name = "invalid:name") void execute(); }
    public interface FailingMessage {
        @MetMessage

        void notifyCaller() throws MappingFailure;
    }
    public static class MappingFailure extends Exception { @MetField(id = 1) private int code; }
    public static class PartialDeclaration {
        @MetField(name = "remote_text")
        private String text;

        @MetField(id = 7)
        public String getText() {
            return this.text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    public static class ConflictingDeclaration {
        @MetField(id = 7)
        private String text;

        @MetField(id = 8)
        public String getText() {
            return this.text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

}
