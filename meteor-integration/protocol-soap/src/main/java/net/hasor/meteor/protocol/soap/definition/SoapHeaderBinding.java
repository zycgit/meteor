/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.util.List;

/** WSDL 声明的一个 SOAP Header 及其可选 headerfault。 */
public final class SoapHeaderBinding {
    private final SoapPart                part;
    private final String                  use;
    private final String                  encodingStyle;
    private final List<SoapHeaderBinding> faults;

    SoapHeaderBinding(SoapPart part, String use, String encodingStyle, List<SoapHeaderBinding> faults) {
        this.part = part;
        this.use = use;
        this.encodingStyle = encodingStyle;
        this.faults = List.copyOf(faults);
    }

    public SoapPart part() {
        return this.part;
    }

    public String use() {
        return this.use;
    }

    public String encodingStyle() {
        return this.encodingStyle;
    }

    public List<SoapHeaderBinding> faults() {
        return this.faults;
    }
}
