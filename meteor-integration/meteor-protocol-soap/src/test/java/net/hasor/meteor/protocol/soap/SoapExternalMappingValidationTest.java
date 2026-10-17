/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap;
import java.util.Map;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.soap.annotation.MetXmlAttribute;
import net.hasor.meteor.protocol.soap.annotation.MetXmlElement;
import net.hasor.meteor.protocol.soap.annotation.MetXmlValue;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.Test;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SoapExternalMappingValidationTest {
    @Test
    public void validNestedDtoCanUseAnExternalContract() throws Exception {
        // 现有 WSDL 与普通两层 DTO 绑定，发布不依赖自动契约生成。
        ServiceDomain<ValidService> service = this.service(ValidService.class);
        ProtocolConfig config = this.config();

        // 完整遍历类型图后仍接受合法的默认映射。
        new SoapProtocol().validateService(config, service);
    }

    @Test
    public void invalidNestedTextMappingIsRejectedAtPublication() throws Exception {
        // 错误只在第二层 DTO：同时声明文本与子元素。
        ServiceDomain<MixedService> service = this.service(MixedService.class);
        ProtocolConfig config = this.config();

        // 使用外部契约也应在发布时失败，不拖到首条网络请求。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                new SoapProtocol().validateService(config, service));
        assertTrue(failure.getMessage().contains("not child elements"));
    }

    @Test
    public void duplicateNestedQNameIsRejectedAtPublication() throws Exception {
        // 第二层 DTO 的两个 Java 属性误用同一个 QName。
        ServiceDomain<DuplicateService> service = this.service(DuplicateService.class);
        ProtocolConfig config = this.config();

        // 防止运行期发生静默覆盖。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                new SoapProtocol().validateService(config, service));
        assertTrue(failure.getMessage().contains("duplicate XML property name"));
    }

    @Test
    public void encodedBusinessIdCannotOverwriteGraphIdentity() throws Exception {
        // SOAP 1.1 的无命名空间 id 是图身份，不能由业务字段覆盖。
        ServiceDomain<ReservedIdService> service = this.service(ReservedIdService.class);
        SoapWsdlResource resource = new SoapWsdlResource( //
                this.getClass().getResource("/wsdl/nested-encoded-mapping.wsdl"));
        service.setBindResource(resource);
        ProtocolConfig config = this.config();

        // 外部契约也在发布时拒绝冲突，而不是等 href 失效才报错。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                new SoapProtocol().validateService(config, service));
        assertTrue(failure.getMessage().contains("attribute is reserved"));
    }

    @Test
    public void literalBusinessIdRemainsARegularAttribute() throws Exception {
        // 同一个 Java DTO 在 literal 中的 id 是普通业务属性。
        ServiceDomain<ReservedIdService> service = this.service(ReservedIdService.class);
        ProtocolConfig config = this.config();

        // encoded 的控制约束不影响 literal 发布。
        new SoapProtocol().validateService(config, service);
    }

    private ProtocolConfig config() {
        return ProtocolConfig.newConfig("soap", "soap", Map.of());
    }

    private <T> ServiceDomain<T> service(Class<T> type) throws Exception {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("soap", "ExternalMapping");
        service.setBindResource(new SoapWsdlResource(this.getClass().getResource("/wsdl/nested-mapping.wsdl")));
        return service;
    }

    public interface ValidService {
        String echo(ValidOuter value);
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
        @MetXmlValue
        public String text;
        public String name;
    }

    public static class DuplicateOuter {
        public DuplicateInner inner;
    }

    public static class DuplicateInner {
        @MetXmlElement(name = "name")
        public String first;
        @MetXmlElement(name = "name")
        public String second;
    }

    public interface ReservedIdService {
        String echo(ReservedIdOuter value);
    }

    public static class ReservedIdOuter {
        public ReservedIdInner inner;
    }

    public static class ReservedIdInner {
        @MetXmlAttribute(name = "id")
        public String id;
        public String name;
    }

}
