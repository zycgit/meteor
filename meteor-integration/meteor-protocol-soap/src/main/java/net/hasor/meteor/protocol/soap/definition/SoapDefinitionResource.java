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
import java.util.Set;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.w3c.dom.Element;

/** SOAP 插件使用的调用契约及其描述文档。 */
public abstract class SoapDefinitionResource {
    public static SoapDefinitionResource create(ProtocolConfig config, ProtocolMeta meta) {
        return create(config, meta, SoapXmlMapping.newMapper());
    }

    /** 使用协议挂载持有的映射配置生成契约，与运行期编解码保持一致。 */
    public static SoapDefinitionResource create(ProtocolConfig config, ProtocolMeta meta, XmlMapper mapper) {
        SoapVersion version = SoapVersion.from(config);
        // 对接已有外部契约时使用显式资源，普通 Java 服务直接生成描述。
        Object source = meta.getBindResource();
        if (source != null && !(source instanceof SoapDefinitionResource)) {
            throw new IllegalArgumentException("SOAP requires a SoapDefinitionResource");
        }

        if (source instanceof SoapDefinitionResource resource) {
            if (resource.definition().version() != version) {
                String message = "WSDL SOAP version does not match soapVersion: ";
                throw new IllegalArgumentException(message + version.displayName());
            }
            if (resource instanceof SoapWsdlResource wsdl) {
                return wsdl.parameterStyle(config.option("soapParameterStyle", "auto"));
            }
            return resource;
        }

        return new SoapGeneratedDefinitionResource(meta.getService(), config, mapper);
    }

    /** 校验生成契约的绑定选项，返回本次实际使用的参数包装方式。 */
    public static String parameterStyle(ProtocolConfig config) {
        String style = option(config, "soapStyle", "document", Set.of("document", "rpc"));
        String use = option(config, "soapUse", "literal", Set.of("literal", "encoded"));
        boolean encodedDocument = StringUtils.equals("document", style) && StringUtils.equals("encoded", use);
        String fallback = encodedDocument ? "bare" : "wrapped";
        String parameters = option(config, "soapParameterStyle", fallback, Set.of("wrapped", "bare"));
        boolean bare = StringUtils.equals("bare", parameters);
        if (StringUtils.equals("rpc", style) && bare) {
            throw new IllegalArgumentException("RPC SOAP style requires wrapped parameters");
        }
        if (encodedDocument && !bare) {
            throw new IllegalArgumentException("Document encoded SOAP requires bare parameters");
        }
        return parameters;
    }

    private static String option(ProtocolConfig config, String name, String fallback, Set<String> values) {
        String selected = config.option(name, fallback);
        if (!values.contains(selected)) {
            throw new IllegalArgumentException("Unsupported SOAP " + name + ": " + selected);
        }
        return selected;
    }

    public abstract SoapDefinition definition();

    /** 已准备 XSD 的纯数据描述，不重新加载资源或编译 Schema。 */
    public abstract ObjectNode describeSchemas();

    public abstract void validateBody(Element body, SoapBody binding) throws IOException;

    public abstract void validatePart(Element payload, SoapPart part) throws IOException;

    public abstract byte[] renderWsdl(URI endpoint) throws IOException;

    public abstract byte[] renderResource(String name, URI endpoint) throws IOException;
}
