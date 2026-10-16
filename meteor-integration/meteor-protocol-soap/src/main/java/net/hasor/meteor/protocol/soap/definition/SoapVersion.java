/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
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

    public String clientFaultCode() {
        return this == SOAP_11 ? "Client" : "Sender";
    }

    public String serverFaultCode() {
        return this == SOAP_11 ? "Server" : "Receiver";
    }
}
