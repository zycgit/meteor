/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.support;
import java.util.Set;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.soap.SoapHeaderHandler;
import org.w3c.dom.Element;

/** 示例只处理 Trace header，未识别的必选头仍交由协议拒绝。 */
public final class TraceHeaderHandler implements SoapHeaderHandler {
    public static final  String ROLE      = "urn:example:trace:processor";
    private static final String NAMESPACE = "urn:example:trace";

    @Override
    public Set<String> roles() {
        return Set.of(ROLE);
    }

    @Override
    public boolean read(Element header, Payload message, boolean request) {
        boolean traceNamespace = StringUtils.equals(NAMESPACE, header.getNamespaceURI());
        boolean trace = traceNamespace && StringUtils.equals("Trace", header.getLocalName());
        if (!trace) {
            return false;
        }
        String option = request ? "requestTrace" : "responseTrace";
        message.addOption(option, header.getTextContent());
        return true;
    }

    @Override
    public void write(Element header, Payload message, boolean request) {
        Element trace = header.getOwnerDocument().createElementNS(NAMESPACE, "trace:Trace");
        String soapNamespace = header.getNamespaceURI();
        boolean soap11 = StringUtils.equals("http://schemas.xmlsoap.org/soap/envelope/", soapNamespace);
        String roleName = soap11 ? "soap:actor" : "soap:role";
        trace.setAttributeNS(soapNamespace, roleName, ROLE);
        trace.setAttributeNS(soapNamespace, "soap:mustUnderstand", soap11 ? "1" : "true");
        trace.setTextContent(request ? "client trace" : "server trace");
        header.appendChild(trace);
    }
}
