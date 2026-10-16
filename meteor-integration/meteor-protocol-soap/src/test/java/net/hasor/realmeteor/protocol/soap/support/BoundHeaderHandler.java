/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap.support;
import java.util.Set;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.soap.SoapHeaderHandler;
import org.w3c.dom.Element;

/** 通过标准 Header 扩展读写 WSDL 声明的整数令牌与回执。 */
public final class BoundHeaderHandler implements SoapHeaderHandler {
    private static final String NAMESPACE = "urn:meteor:test:bound-header-fault";

    @Override
    public Set<String> roles() {
        return Set.of();
    }

    @Override
    public boolean read(Element header, Payload message, boolean request) {
        String name = request ? "token" : "receipt";
        boolean boundNamespace = StringUtils.equals(NAMESPACE, header.getNamespaceURI());
        boolean recognized = boundNamespace && StringUtils.equals(name, header.getLocalName());
        if (!recognized) {
            return false;
        }
        message.addOption(name, header.getTextContent());
        return true;
    }

    @Override
    public void write(Element header, Payload message, boolean request) {
        String name = request ? "token" : "receipt";
        Element value = header.getOwnerDocument().createElementNS(NAMESPACE, "bound:" + name);
        value.setAttributeNS(header.getNamespaceURI(), "soap:mustUnderstand", "true");
        value.setTextContent(request ? "17" : "29");
        header.appendChild(value);
    }
}
