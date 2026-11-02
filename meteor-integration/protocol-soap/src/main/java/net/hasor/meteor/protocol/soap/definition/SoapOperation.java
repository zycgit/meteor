/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

/** WSDL 操作及两个方向的 SOAP 消息绑定。 */
public final class SoapOperation {
    private final String                        name;
    private final String                        soapAction;
    private final String                        style;
    private final SoapBody                      inputBody;
    private final SoapBody                      outputBody;
    private final boolean                       actionRequired;
    private final List<SoapHeaderBinding>       inputHeaders;
    private final List<SoapHeaderBinding>       outputHeaders;
    private final Map<String, SoapFaultBinding> faults;

    SoapOperation(String name, String soapAction, String style, SoapBody inputBody, //
            SoapBody outputBody, boolean actionRequired, List<SoapHeaderBinding> inputHeaders, //
            List<SoapHeaderBinding> outputHeaders, Map<String, SoapFaultBinding> faults) {
        this.name = name;
        this.soapAction = soapAction;
        this.style = style;
        this.inputBody = inputBody;
        this.outputBody = outputBody;
        this.actionRequired = actionRequired;
        this.inputHeaders = List.copyOf(inputHeaders);
        this.outputHeaders = List.copyOf(outputHeaders);
        this.faults = Map.copyOf(faults);
    }

    public String name() {
        return this.name;
    }

    public String soapAction() {
        return this.soapAction;
    }

    public String style() {
        return this.style;
    }

    public boolean actionRequired() {
        return this.actionRequired;
    }

    public List<SoapHeaderBinding> inputHeaders() {
        return this.inputHeaders;
    }

    public List<SoapHeaderBinding> outputHeaders() {
        return this.outputHeaders;
    }

    public Map<String, SoapFaultBinding> faults() {
        return this.faults;
    }

    public SoapBody inputBody() {
        return this.inputBody;
    }

    public SoapBody outputBody() {
        return this.outputBody;
    }

    QName inputElement() {
        return this.firstElement(this.inputBody);
    }

    QName outputElement() {
        return this.firstElement(this.outputBody);
    }

    List<QName> parameterElements() {
        return this.inputBody.elements();
    }

    QName resultElement() {
        if (this.outputBody == null || this.outputBody.parts().isEmpty()) {
            return null;
        }
        return this.outputBody.parts().get(0).element();
    }

    public boolean oneWay() {
        return this.outputBody == null;
    }

    private QName firstElement(SoapBody body) {
        if (body == null) {
            return null;
        }
        if (body.wrapper() != null) {
            return body.wrapper();
        }
        return body.parts().isEmpty() ? null : body.parts().get(0).element();
    }
}
