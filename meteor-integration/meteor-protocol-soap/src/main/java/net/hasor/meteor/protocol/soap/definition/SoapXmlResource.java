/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.net.URL;
import java.util.Objects;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;
import org.w3c.dom.Document;

/** 单个 WSDL 或 XSD 资源的解析结果。 */
final class SoapXmlResource {
    private final String   name;
    private final URL      source;
    private final byte[]   body;
    private final Document document;

    SoapXmlResource(String name, URL source, byte[] body, Document document) {
        this.name = name;
        this.source = source;
        this.body = body.clone();
        this.document = document;
    }

    public String name() {
        return this.name;
    }

    public URL source() {
        return this.source;
    }

    public byte[] body() {
        return this.body.clone();
    }

    public Document document() {
        return this.document;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof SoapXmlResource that)) {
            return false;
        }
        return this.body == that.body && //
                StringUtils.equals(this.name, that.name) && //
                ObjectUtils.equals(this.source, that.source) && //
                ObjectUtils.equals(this.document, that.document);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.name, this.source, this.body, this.document);
    }

    @Override
    public String toString() {
        return "SoapXmlResource[name=" + this.name + ", source=" + this.source + ", body=" + this.body + //
                ", document=" + this.document + "]";
    }
}
