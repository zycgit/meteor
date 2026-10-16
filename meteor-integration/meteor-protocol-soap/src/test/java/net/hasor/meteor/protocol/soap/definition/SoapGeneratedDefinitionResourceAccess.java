/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.ProtocolConfig;

/** 为单元测试提供生成契约和共享 XML 映射器的包内访问。 */
public final class SoapGeneratedDefinitionResourceAccess {
    private final SoapGeneratedDefinitionResource resource;

    public SoapGeneratedDefinitionResourceAccess(MetBindInfo<?> service) {
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        this.resource = new SoapGeneratedDefinitionResource(service, config);
    }

    public static byte[] renderWsdl(ProtocolConfig config, MetBindInfo<?> service, URI endpoint) throws IOException {
        return SoapDefinitionResource.create(config, service).renderWsdl(endpoint);
    }

    public String targetNamespace() {
        return this.resource.definition().targetNamespace();
    }

    public String soapAction(String operation) {
        return this.resource.definition().operation(operation).soapAction();
    }

    public QName inputElement(String operation) {
        return this.resource.definition().operation(operation).inputElement();
    }

    public QName outputElement(String operation) {
        return this.resource.definition().operation(operation).outputElement();
    }

    public List<QName> parameterElements(String operation) {
        return this.resource.definition().operation(operation).parameterElements();
    }

    public QName resultElement(String operation) {
        return this.resource.definition().operation(operation).resultElement();
    }

    public boolean oneWay(String operation) {
        return this.resource.definition().operation(operation).oneWay();
    }

    public byte[] renderWsdl(URI endpoint) throws IOException {
        return this.resource.renderWsdl(endpoint);
    }

    public byte[] renderResource(String name, URI endpoint) throws IOException {
        return this.resource.renderResource(name, endpoint);
    }

}
