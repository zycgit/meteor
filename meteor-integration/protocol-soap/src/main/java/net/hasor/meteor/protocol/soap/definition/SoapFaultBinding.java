/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;

/** WSDL 声明的 Fault detail 及序列化规则。 */
public final class SoapFaultBinding {
    private final SoapPart part;
    private final String   use;
    private final String   encodingStyle;

    SoapFaultBinding(SoapPart part, String use, String encodingStyle) {
        this.part = part;
        this.use = use;
        this.encodingStyle = encodingStyle;
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
}
