/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.annotation.MetType;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SoapExternalMappingValidationTest {
    @Test
    public void validNestedDtoCanUseAnExternalContract() throws Exception {
        // 现有 WSDL 与普通两层 DTO 绑定，发布不依赖自动契约生成。
        ServiceDomain<ValidService> service = this.service(ValidService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 完整遍历类型图后仍接受合法的默认映射。
        assertNotNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void objectMapCanUseAnExternalLiteralContract() throws Exception {
        // 外部契约的 inner/name 结构也能由 Map 的动态 name key 承载。
        ServiceDomain<MapValueService> service = this.service(MapValueService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // Map<String,Object> 的 Object 是开放内容，不继续按普通 DTO 校验。
        assertNotNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void dtoMapValuesCanUseAnExternalLiteralContract() throws Exception {
        // 动态 key 和 DTO value 分别决定名称和结构，声明泛型可直接解析 DTO。
        ServiceDomain<DtoMapValueService> service = this.service(DtoMapValueService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 外部契约绑定沿着 Map value 的 User 类型校验，不依赖运行时样本。
        assertNotNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void objectParameterCanUseAnExternalLiteralContract() throws Exception {
        // Object 参数不提供固定 DTO 结构，绑定时作为开放元素终止。
        ServiceDomain<ObjectValueService> service = this.service(ObjectValueService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        assertNotNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void nestedCollectionsCanUseAnExternalLiteralContract() throws Exception {
        // Map、List、Set 嵌套后仍沿声明泛型遍历，Object 作为最终开放条目。
        ServiceDomain<NestedCollectionService> service = this.service(NestedCollectionService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        assertNotNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void rawListCanUseAnExternalLiteralContract() throws Exception {
        // 原始 List 的条目类型是 Object，绑定无需先拿到一条非空数据。
        ServiceDomain<RawListService> service = this.service(RawListService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        assertNotNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void invalidNestedMapKeyIsRejectedAtPublication() throws Exception {
        // 第二层 Map 的非 String key 不能因外层泛型已通过而漏掉。
        ServiceDomain<InvalidMapKeyService> service = this.service(InvalidMapKeyService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void invalidDtoInsideMapIsRejectedAtPublication() throws Exception {
        // Map value 的 DTO 同时有文本和子元素，声明类型图必须在发布时发现错误。
        ServiceDomain<InvalidMapDtoService> service = this.service(InvalidMapDtoService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                new SoapProtocol().prepareBinding(config, null, meta));
        assertTrue(failure.getMessage().contains("not child elements"));
    }

    @Test
    public void inheritedMapValueTypeIsValidatedAtPublication() throws Exception {
        // 父接口声明 Map<String,T>，实际发布接口把 T 绑定为非法 DTO。
        ServiceDomain<InheritedInvalidMapService> service = this.service(InheritedInvalidMapService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 不能把已绑定的 T 退化成 Object，漏过 DTO 本身的映射错误。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                new SoapProtocol().prepareBinding(config, null, meta));
        assertTrue(failure.getMessage().contains("not child elements"));
    }

    @Test
    public void inheritedListParameterCanBindARepeatedPart() throws Exception {
        // 泛型接口中的 List<T> 解析后仍是集合，可绑定外部契约的重复元素。
        ServiceDomain<StringNotificationService> service = this.service(StringNotificationService.class);
        Object bindResource = new SoapWsdlResource(this.getClass().getResource("/wsdl/repeated.wsdl"));
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());

        assertNotNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void optionalPartStillRejectsPrimitiveParameter() throws Exception {
        // 声明类型统一解析后仍须保留 primitive 无法承载缺失值的约束。
        ServiceDomain<PrimitiveNotificationService> service = this.service(PrimitiveNotificationService.class);
        Object bindResource = new SoapWsdlResource(this.getClass().getResource("/wsdl/optional.wsdl"));
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());

        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void objectParameterRemainsRejectedWithAnExternalEncodedContract() throws Exception {
        // 本次开放内容映射只适用于 literal，不改变 encoded 的固定类型约束。
        ServiceDomain<ObjectValueService> service = this.service(ObjectValueService.class);
        Object bindResource = new SoapWsdlResource( //
                this.getClass().getResource("/wsdl/nested-encoded-mapping.wsdl"));
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());

        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void objectListRemainsRejectedWithAnExternalEncodedContract() throws Exception {
        // encoded 的 Object 集合不能借用 literal 的 anyType 映射绕过声明校验。
        ServiceDomain<ObjectListService> service = this.service(ObjectListService.class);
        Object bindResource = new SoapWsdlResource( //
                this.getClass().getResource("/wsdl/nested-encoded-mapping.wsdl"));
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());

        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void dynamicMapsAreRejectedWithAnExternalEncodedContract() throws Exception {
        // 同一 Map DTO 在外部 Encoding 契约中仍明确拒绝，不能生成空对象图。
        ServiceDomain<MapValueService> service = this.service(MapValueService.class);
        Object bindResource = new SoapWsdlResource( //
                this.getClass().getResource("/wsdl/nested-encoded-mapping.wsdl"));
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());

        assertNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void parameterWrapperIsRejectedWithAnExternalContract() throws Exception {
        // 外部 WSDL 绕过契约生成器，参数仍不能使用 DTO 集合属性的包装声明。
        ServiceDomain<WrapperParameterService> service = this.service(WrapperParameterService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 发布入口必须检查注解，不能把包装声明悄悄丢弃。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertTrue(failure.getMessage().contains("only supported on DTO properties"));
    }

    @Test
    public void operationElementOptionsRemainValidWithAnExternalContract() throws Exception {
        // 普通服务元素约束不包含 DTO 集合属性的包装声明。
        ServiceDomain<MappedElementService> service = this.service(MappedElementService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 外部 WSDL 发布入口不应把 namespace、required 或 nillable 当成包装配置拒绝。
        assertNotNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void resultWrapperIsRejectedWithAnExternalContract() throws Exception {
        // 已有 WSDL 定义返回元素，方法注解不能额外声明 DTO 集合属性的包装层。
        ServiceDomain<WrapperResultService> service = this.service(WrapperResultService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 外部契约与自动契约保持相同的配置位置约束。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertTrue(failure.getMessage().contains("only supported on DTO properties"));
    }

    @Test
    public void invalidNestedTextMappingIsRejectedAtPublication() throws Exception {
        // 错误只在第二层 DTO：同时声明文本与子元素。
        ServiceDomain<MixedService> service = this.service(MixedService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 使用外部契约也应在发布时失败，不拖到首条网络请求。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                new SoapProtocol().prepareBinding(config, null, meta));
        assertTrue(failure.getMessage().contains("not child elements"));
    }

    @Test
    public void duplicateNestedQNameIsRejectedAtPublication() throws Exception {
        // 第二层 DTO 的两个 Java 属性误用同一个 QName。
        ServiceDomain<DuplicateService> service = this.service(DuplicateService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 防止运行期发生静默覆盖。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                new SoapProtocol().prepareBinding(config, null, meta));
        assertTrue(failure.getMessage().contains("duplicate XML property name"));
    }

    @Test
    public void encodedBusinessIdCannotOverwriteGraphIdentity() throws Exception {
        // SOAP 1.1 的无命名空间 id 是图身份，不能由业务字段覆盖。
        ServiceDomain<ReservedIdService> service = this.service(ReservedIdService.class);
        SoapWsdlResource resource = new SoapWsdlResource( //
                this.getClass().getResource("/wsdl/nested-encoded-mapping.wsdl"));
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of());

        // 外部契约也在发布时拒绝冲突，而不是等 href 失效才报错。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                new SoapProtocol().prepareBinding(config, null, meta));
        assertTrue(failure.getMessage().contains("attribute is reserved"));
    }

    @Test
    public void literalBusinessIdRemainsARegularAttribute() throws Exception {
        // 同一个 Java DTO 在 literal 中的 id 是普通业务属性。
        ServiceDomain<ReservedIdService> service = this.service(ReservedIdService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // encoded 的控制约束不影响 literal 发布。
        assertNotNull(new SoapProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void multipartResultAttributeIsRejectedAtPublication() throws Exception {
        // 多 part 返回对象只聚合值，result 字段没有可承载 attribute 的父元素。
        ServiceDomain<MultipartAttributeService> service = this.service(MultipartAttributeService.class);
        SoapWsdlResource resource = new SoapWsdlResource( //
                this.getClass().getResource("/wsdl/styles/document-multipart.wsdl"));
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of());

        // 外部 WSDL 发布入口必须发现问题，不能到报文输出时把 attribute 变成元素。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertTrue(failure.getMessage().contains("XML attribute has no parent element"));
        assertTrue(failure.getMessage().contains(MultipartAttributeResult.class.getName() + ".result"));
    }

    @Test
    public void operationAttributeIsRejectedWithAnExternalContract() throws Exception {
        // 服务方法不是 DTO 属性，JacksonXmlProperty 不能装饰整个 operation。
        ServiceDomain<AttributeOperationService> service = this.service(AttributeOperationService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = service.getProtocolMeta("soap");

        // 外部 WSDL 也不能绕过方法注解校验，错误说明没有实际父元素的位置。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapProtocol().prepareBinding(config, null, meta));
        assertTrue(failure.getMessage().contains("XML attribute"));
        assertTrue(failure.getMessage().contains("no parent element"));
        assertTrue(failure.getMessage().contains(AttributeOperationService.class.getName()));
        assertTrue(failure.getMessage().contains("echo"));
    }

    private ProtocolConfig config() {
        return ProtocolConfig.newConfig("soap", "soap", Map.of());
    }

    private <T> ServiceDomain<T> service(Class<T> type) throws Exception {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("soap", "ExternalMapping");
        service.setBindResource("soap", new SoapWsdlResource(this.getClass().getResource("/wsdl/nested-mapping.wsdl")));
        return service;
    }

    public interface ValidService {
        String echo(ValidOuter value);
    }

    public interface MapValueService {
        String echo(MapOuter value);
    }

    public static class MapOuter {
        public Map<String, Object> inner;
    }

    public interface DtoMapValueService {
        String echo(DtoMapOuter value);
    }

    public static class DtoMapOuter {
        public Map<String, ValidInner> inner;
    }

    public interface ObjectValueService {
        String echo(Object value);
    }

    public interface NestedCollectionService {
        String echo(Map<String, List<Set<Object>>> value);
    }

    public interface RawListService {
        String echo(List value);
    }

    public interface ObjectListService {
        String echo(List<Object> value);
    }

    public interface InvalidMapKeyService {
        String echo(Map<String, List<Map<Integer, ValidInner>>> value);
    }

    public interface InvalidMapDtoService {
        String echo(Map<String, MixedInner> value);
    }

    public interface GenericMapService<T> {
        String echo(Map<String, T> value);
    }

    public interface InheritedInvalidMapService extends GenericMapService<MixedInner> {
    }

    public interface NotificationService<T> {
        @MetMessage
        void notify(List<T> values);
    }

    public interface StringNotificationService extends NotificationService<String> {
    }

    public interface PrimitiveNotificationService {
        @MetMessage
        void notify(int value);
    }

    public interface WrapperParameterService {
        String echo(@JacksonXmlElementWrapper(localName = "items") ValidOuter value);
    }

    public interface WrapperResultService {
        @JacksonXmlElementWrapper(localName = "result")
        String echo(ValidOuter value);
    }

    public interface MappedElementService {
        @JacksonXmlProperty(namespace = "urn:test:results")
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        String echo(@JacksonXmlProperty(namespace = "urn:test:args") @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) ValidOuter value);
    }

    public interface MixedService {
        String echo(MixedOuter value);
    }

    public interface DuplicateService {
        String echo(DuplicateOuter value);
    }

    public static class ValidOuter {
        public ValidInner inner;
    }

    public static class ValidInner {
        public String name;
    }

    public static class MixedOuter {
        public MixedInner inner;
    }

    public static class MixedInner {
        @JacksonXmlText
        public String text;
        public String name;
    }

    public static class DuplicateOuter {
        public DuplicateInner inner;
    }

    public static class DuplicateInner {
        @MetField(name = "name")
        public String first;
        @MetField(name = "name")
        public String second;
    }

    public interface ReservedIdService {
        String echo(ReservedIdOuter value);
    }

    public static class ReservedIdOuter {
        public ReservedIdInner inner;
    }

    public static class ReservedIdInner {
        @MetField(name = "id")
        @JacksonXmlProperty(isAttribute = true)
        public String id;
        public String name;
    }

    public interface MultipartAttributeService {
        MultipartAttributeResult combine(String first, String last);
    }

    @MetType(name = "MultipartAttributeResult")
    public static class MultipartAttributeResult {
        @MetField(name = "fullName")
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:meteor:test:styles:multipart")
        public String result;
        public int    size;
    }

    public interface AttributeOperationService {
        @JacksonXmlProperty(isAttribute = true)
        String echo(ValidOuter value);
    }

}
