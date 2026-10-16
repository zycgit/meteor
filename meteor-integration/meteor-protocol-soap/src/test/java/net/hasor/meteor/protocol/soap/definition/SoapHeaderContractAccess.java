/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import javax.xml.namespace.QName;

/** WSDL header/fault 契约的只读包内访问。 */
public final class SoapHeaderContractAccess {
    private SoapHeaderContractAccess() {
    }

    public static QName inputHeader(SoapWsdlResource resource) {
        return resource.definition().operation("echo").inputHeaders().get(0).part().element();
    }

    public static QName outputHeader(SoapWsdlResource resource) {
        return resource.definition().operation("echo").outputHeaders().get(0).part().element();
    }

    public static QName headerFault(SoapWsdlResource resource) {
        return resource.definition().operation("echo").inputHeaders().get(0).faults().get(0).part().element();
    }

    public static QName fault(SoapWsdlResource resource) {
        return resource.definition().operation("echo").faults().get("Problem").part().element();
    }

    public static String faultUse(SoapWsdlResource resource) {
        return resource.definition().operation("echo").faults().get("Problem").use();
    }

    public static int inputPartCount(SoapWsdlResource resource) {
        return resource.definition().operation("echo").inputBody().parts().size();
    }
}
