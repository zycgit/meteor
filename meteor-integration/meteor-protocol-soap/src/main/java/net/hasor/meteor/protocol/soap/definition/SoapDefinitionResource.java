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
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.ProtocolConfig;
import org.w3c.dom.Element;

/** SOAP 插件使用的调用契约及其描述文档。 */
public abstract class SoapDefinitionResource {
    public static SoapDefinitionResource create(ProtocolConfig config, MetBindInfo<?> service) {
        SoapVersion version = SoapVersion.from(config);
        // 对接已有外部契约时使用显式资源，普通 Java 服务直接生成描述。
        if (service.getBindResource() instanceof SoapDefinitionResource resource) {
            if (resource.definition().version() != version) {
                String message = "WSDL SOAP version does not match soapVersion: ";
                throw new IllegalArgumentException(message + version.displayName());
            }
            if (resource instanceof SoapWsdlResource wsdl) {
                return wsdl.parameterStyle(config.option("soapParameterStyle", "auto"));
            }
            return resource;
        }
        return new SoapGeneratedDefinitionResource(service, config);
    }

    public abstract SoapDefinition definition();

    public abstract void validateBody(Element body, SoapBody binding) throws IOException;

    public abstract void validatePart(Element payload, SoapPart part) throws IOException;

    public abstract byte[] renderWsdl(URI endpoint) throws IOException;

    public abstract byte[] renderResource(String name, URI endpoint) throws IOException;
}
