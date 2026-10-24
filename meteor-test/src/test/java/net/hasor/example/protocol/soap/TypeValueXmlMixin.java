/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/** 列表容器属于 SOAP 契约映射，保留空列表而不修改跨协议业务 DTO。 */
@JsonPropertyOrder({ "caseId", "booleanValue", "int8Value", "int16Value", "int32Value", "int64Value", //
        "float32Value", "float64Value", "textValue", "binaryValue", "enumValue", "item", //
        "mapValue", "recordValue", "optionalValue", "receipt", "unsigned64Value" })
public abstract class TypeValueXmlMixin {
    @JacksonXmlElementWrapper(localName = "listValue")
    @JacksonXmlProperty(localName = "item")
    public abstract List<String> getListValue();
}
