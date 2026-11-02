/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import org.w3c.dom.Element;

/** 整个 DTO 只经过一次 Jackson writer/reader，SOAP 形状转换不再次访问 bean 属性。 */
final class SoapJacksonValueCodec {
    private final XmlMapper mapper;

    SoapJacksonValueCodec(XmlMapper mapper) {
        this.mapper = mapper.copy();
        this.mapper.setSerializerFactory(new SoapJacksonSerializerFactory(this.mapper.getSerializerFactory()));
        try {
            SerializerProvider provider = mapper.getSerializerProviderInstance();
            String serializerName = provider.findValueSerializer(Element.class).getClass().getName();
            if (StringUtils.equals("com.fasterxml.jackson.databind.ext.DOMSerializer", serializerName)) {
                SimpleModule domValues = new SimpleModule("meteor-soap-dom-values");
                domValues.addSerializer(Element.class, new JsonSerializer<>() {
                    @Override
                    public void serialize(Element value, JsonGenerator generator, SerializerProvider provider) //
                            throws IOException {
                        generator.writeEmbeddedObject(value);
                    }
                });
                this.mapper.registerModule(domValues);
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to prepare SOAP Jackson value binding", failure);
        }
    }

    SoapJacksonValue write(JavaType type, Object value) throws IOException {
        List<Object> sources = new ArrayList<>();
        try (TokenBuffer buffer = new TokenBuffer(this.mapper, false)) {
            SerializerProvider provider = this.mapper.getSerializerProviderInstance();
            JsonGenerator generator = new JsonGeneratorDelegate(buffer, false) {
                @Override
                public void writeObject(Object value) throws IOException {
                    // 自定义读写器的嵌套值继续使用当前 provider，不能重新进入 XmlMapper 的 XML 根写入。
                    provider.defaultSerializeValue(value, this);
                }

                @Override
                public void writeObjectField(String name, Object value) throws IOException {
                    this.writeFieldName(name);
                    this.writeObject(value);
                }

                @Override
                public void writeTree(TreeNode tree) throws IOException {
                    provider.defaultSerializeValue(tree, this);
                }

                @Override
                public void writeStartObject() throws IOException {
                    sources.add(null);
                    super.writeStartObject();
                }

                @Override
                public void writeStartObject(Object value) throws IOException {
                    sources.add(value);
                    super.writeStartObject(value);
                }

                @Override
                public void writeStartObject(Object value, int size) throws IOException {
                    sources.add(value);
                    super.writeStartObject(value, size);
                }

                @Override
                public void writeStartArray() throws IOException {
                    sources.add(null);
                    super.writeStartArray();
                }

                @Override
                public void writeStartArray(int size) throws IOException {
                    sources.add(null);
                    super.writeStartArray(size);
                }

                @Override
                public void writeStartArray(Object value) throws IOException {
                    sources.add(value);
                    super.writeStartArray(value);
                }

                @Override
                public void writeStartArray(Object value, int size) throws IOException {
                    sources.add(value);
                    super.writeStartArray(value, size);
                }

                @Override
                public void writeArray(int[] values, int offset, int length) throws IOException {
                    sources.add(values);
                    super.writeArray(values, offset, length);
                }

                @Override
                public void writeArray(long[] values, int offset, int length) throws IOException {
                    sources.add(values);
                    super.writeArray(values, offset, length);
                }

                @Override
                public void writeArray(double[] values, int offset, int length) throws IOException {
                    sources.add(values);
                    super.writeArray(values, offset, length);
                }

                @Override
                public void writeArray(String[] values, int offset, int length) throws IOException {
                    sources.add(values);
                    super.writeArray(values, offset, length);
                }
            };
            try {
                TypeSerializer typeSerializer = this.mapper.getSerializerFactory() //
                        .createTypeSerializer(this.mapper.getSerializationConfig(), type);
                JavaType runtimeType = this.mapper.getTypeFactory().constructSpecializedType(type, value.getClass());
                if (typeSerializer != null) {
                    JsonSerializer<Object> serializer = provider.findValueSerializer(runtimeType, null);
                    serializer.serializeWithType(value, generator, provider, typeSerializer);
                } else {
                    JavaType serializerType = type.hasRawClass(Object.class) ? runtimeType : type;
                    JsonSerializer<Object> serializer = provider.findTypedValueSerializer(serializerType, true, null);
                    serializer.serialize(value, generator, provider);
                }
            } catch (ClassCastException failure) {
                String reason = "SOAP DTO serializers must support Jackson token generators; " //
                        + "XML-only generators are unsupported";
                throw new IllegalArgumentException(reason, failure);
            }
            try (JsonParser parser = buffer.asParser(this.mapper)) {
                parser.nextToken();
                return this.tokens(parser, sources.iterator(), 0);
            }
        }
    }

    Object read(JavaType type, SoapJacksonValue value) throws IOException {
        try (TokenBuffer buffer = new TokenBuffer(this.mapper, false)) {
            this.tokens(buffer, value, 0);
            try (JsonParser parser = buffer.asParser(this.mapper)) {
                try {
                    return this.mapper.readerFor(type).readValue(parser);
                } catch (ClassCastException failure) {
                    String reason = "SOAP DTO deserializers must support Jackson token parsers; " //
                            + "XML-only parsers are unsupported";
                    throw new IllegalArgumentException(reason, failure);
                }
            }
        }
    }

    private SoapJacksonValue tokens(JsonParser parser, Iterator<Object> sources, int depth) throws IOException {
        this.depth(depth);
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT) {
            Object source = sources.hasNext() ? sources.next() : null;
            Map<String, SoapJacksonValue> fields = new LinkedHashMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.currentName();
                parser.nextToken();
                fields.put(name, this.tokens(parser, sources, depth + 1));
            }
            return new SoapJacksonValue(fields, source);
        }
        if (token == JsonToken.START_ARRAY) {
            Object source = sources.hasNext() ? sources.next() : null;
            List<SoapJacksonValue> items = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                items.add(this.tokens(parser, sources, depth + 1));
            }
            return new SoapJacksonValue(items, source);
        }
        Object value = switch (token) {
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> parser.getNumberValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            case VALUE_EMBEDDED_OBJECT -> parser.getEmbeddedObject();
            default -> throw new IllegalArgumentException("Unsupported SOAP Jackson value token: " + token);
        };
        return new SoapJacksonValue(value, null);
    }

    private void tokens(TokenBuffer target, SoapJacksonValue node, int depth) throws IOException {
        this.depth(depth);
        if (node.object()) {
            target.writeStartObject();
            for (Map.Entry<String, SoapJacksonValue> field : node.fields().entrySet()) {
                target.writeFieldName(field.getKey());
                this.tokens(target, field.getValue(), depth + 1);
            }
            target.writeEndObject();
        } else if (node.array()) {
            target.writeStartArray();
            for (SoapJacksonValue item : node.items()) {
                this.tokens(target, item, depth + 1);
            }
            target.writeEndArray();
        } else {
            Object value = node.value();
            if (value == null) {
                target.writeNull();
            } else if (value instanceof String text) {
                target.writeString(text);
            } else if (value instanceof Boolean bool) {
                target.writeBoolean(bool);
            } else if (value instanceof BigInteger number) {
                target.writeNumber(number);
            } else if (value instanceof BigDecimal number) {
                target.writeNumber(number);
            } else if (value instanceof Double number) {
                target.writeNumber(number);
            } else if (value instanceof Float number) {
                target.writeNumber(number);
            } else if (value instanceof Long number) {
                target.writeNumber(number);
            } else if (value instanceof Number number) {
                target.writeNumber(number.intValue());
            } else {
                target.writeEmbeddedObject(value);
            }
        }
    }

    private void depth(int depth) {
        if (depth > 128) {
            throw new IllegalArgumentException("SOAP Jackson value exceeds nesting depth 128");
        }
    }
}
