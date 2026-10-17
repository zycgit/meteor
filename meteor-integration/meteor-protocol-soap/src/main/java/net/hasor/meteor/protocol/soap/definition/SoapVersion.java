/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.ProtocolConfig;

/** SOAP 消息、HTTP 媒体类型和 WSDL binding 的共同版本。 */
public enum SoapVersion {
    SOAP_11("1.1", //
            "http://schemas.xmlsoap.org/soap/envelope/", //
            "http://schemas.xmlsoap.org/wsdl/soap/", //
            "text/xml"),
    SOAP_12("1.2", //
            "http://www.w3.org/2003/05/soap-envelope", //
            "http://schemas.xmlsoap.org/wsdl/soap12/", //
            "application/soap+xml");

    private final String displayName;
    private final String namespace;
    private final String bindingNamespace;
    private final String contentType;

    SoapVersion(String displayName, String namespace, String bindingNamespace, String contentType) {
        this.displayName = displayName;
        this.namespace = namespace;
        this.bindingNamespace = bindingNamespace;
        this.contentType = contentType;
    }

    public static SoapVersion from(ProtocolConfig config) {
        String value = config.option("soapVersion", "1.1");
        for (SoapVersion version : values()) {
            if (StringUtils.equals(value, version.displayName)) {
                return version;
            }
        }
        throw new IllegalArgumentException("SOAP soapVersion must be 1.1 or 1.2: " + value);
    }

    public String displayName() {
        return this.displayName;
    }

    public String namespace() {
        return this.namespace;
    }

    String bindingNamespace() {
        return this.bindingNamespace;
    }

    public String contentType() {
        return this.contentType;
    }

    /** 判断 Encoding 节点使用的控制属性，业务映射不得覆盖这些 QName。 */
    public boolean isEncodingControlAttribute(QName name) {
        String namespace = name.getNamespaceURI();
        String local = name.getLocalPart();
        boolean legacyGraph = this == SOAP_11 && namespace.isEmpty() && Set.of("id", "href").contains(local);
        String encoding = this == SOAP_11 //
                ? "http://schemas.xmlsoap.org/soap/encoding/" : "http://www.w3.org/2003/05/soap-encoding";
        boolean encodingControl = StringUtils.equals(namespace, encoding);
        boolean xsiControl = StringUtils.equals(namespace, XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI) //
                && Set.of("type", "nil", "null").contains(local);
        boolean envelopeControl = StringUtils.equals(namespace, this.namespace) //
                && StringUtils.equals(local, "encodingStyle");
        boolean namespaceDeclaration = StringUtils.equals(namespace, XMLConstants.XMLNS_ATTRIBUTE_NS_URI);
        return legacyGraph || encodingControl || xsiControl || envelopeControl || namespaceDeclaration;
    }

    public String clientFaultCode() {
        return this == SOAP_11 ? "Client" : "Sender";
    }

    public String serverFaultCode() {
        return this == SOAP_11 ? "Server" : "Receiver";
    }
}
