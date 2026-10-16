/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.*;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.meteor.protocol.soap.definition.*;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 依据一个方向的消息契约映射 Body 与 Java 参数或结果。 */
final class SoapBodyCodec {
    private final SoapVersion           version;
    private final SoapLiteralValueCodec literal;
    private final SoapEncodedValueCodec encoded;
    private final XmlMapper             mapper;

    SoapBodyCodec(SoapVersion version, XmlMapper mapper) {
        this.version = version;
        this.mapper = mapper;
        this.literal = new SoapLiteralValueCodec(mapper);
        this.encoded = new SoapEncodedValueCodec(version, mapper);
    }

    Object[] read(Element body, SoapBody binding, Type[] types) throws Exception {
        this.requireTypes(binding, types);
        Element container = this.container(body, binding);
        List<List<Element>> fields = this.fields(container, binding);
        int[] counts = fields.stream().mapToInt(List::size).toArray();
        this.validateCounts(binding, counts);
        SoapEncodedValueCodec.Decoder decoder = null;
        if (binding.encoded()) {
            decoder = this.encoded.decoder(body);
        }
        Object[] values = new Object[types.length];
        for (int index = 0; index < values.length; index++) {
            SoapPart part = binding.parts().get(index);
            List<Element> elements = fields.get(index);
            if (this.repeated(part)) {
                JavaType collectionType = this.collectionType(types[index]);
                if (elements.isEmpty() && this.choiceMember(binding, index)) {
                    values[index] = null;
                    continue;
                }
                Type itemType = collectionType.getContentType();
                List<Object> items = new ArrayList<>();
                for (Element element : elements) {
                    items.add(this.readValue(element, part, itemType, decoder));
                }
                values[index] = this.collection(collectionType, items);
            } else if (!elements.isEmpty()) {
                values[index] = this.readValue(elements.get(0), part, types[index], decoder);
                if (values[index] == null && this.mapper.constructType(types[index]).isPrimitive()) {
                    throw new IllegalArgumentException("Nil SOAP part cannot map to a primitive: " + part.name());
                }
            } else if (this.mapper.constructType(types[index]).isPrimitive()) {
                throw new IllegalArgumentException("Absent SOAP part cannot map to a primitive: " + part.name());
            }
        }
        return values;
    }

    void write(Element body, SoapBody binding, Type[] types, Object[] values, //
            Map<Element, byte[]> binaries) throws Exception {
        this.requireTypes(binding, types);
        if (values.length != types.length) {
            throw new IllegalArgumentException("SOAP part and Java value counts do not match");
        }
        List<List<?>> fields = new ArrayList<>();
        int[] counts = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            SoapPart part = binding.parts().get(index);
            List<?> items = this.items(part, types[index], values[index]);
            fields.add(items);
            counts[index] = items.size();
        }
        this.validateCounts(binding, counts);

        Element container = body;
        if (binding.wrapper() != null) {
            container = this.append(body, binding.wrapper());
        }
        SoapEncodedValueCodec.Encoder encoder = null;
        if (binding.encoded()) {
            encoder = this.encoded.encoder(body.getOwnerDocument(), body);
        }
        for (int index = 0; index < values.length; index++) {
            SoapPart part = binding.parts().get(index);
            Type itemType = types[index];
            if (this.repeated(part)) {
                itemType = this.collectionType(itemType).getContentType();
            }
            for (Object value : fields.get(index)) {
                if (encoder == null) {
                    Element element = this.append(container, part.element());
                    this.literal.write(element, itemType, value, binaries);
                } else {
                    encoder.write(container, part.element(), part.type(), itemType, value);
                }
            }
        }
        if (encoder != null) {
            encoder.finish();
        }
    }

    void validateEncoded(Element body) throws Exception {
        this.encoded.decoder(body);
    }

    private Object readValue(Element element, SoapPart part, Type type, //
            SoapEncodedValueCodec.Decoder decoder) throws Exception {
        if (decoder != null) {
            return decoder.read(element, part.type(), type);
        }
        return this.literal.read(element, type);
    }

    private Element container(Element body, SoapBody binding) {
        if (binding.wrapper() == null) {
            return body;
        }
        Element selected = null;
        for (Element element : this.elements(body)) {
            if (binding.wrapper().equals(this.name(element))) {
                if (selected != null) {
                    throw new IllegalArgumentException("Duplicate SOAP wrapper: " + binding.wrapper());
                }
                selected = element;
            } else if (!binding.encoded() || !this.referenceValue(element)) {
                throw new IllegalArgumentException("Unexpected SOAP Body element: " + this.name(element));
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("SOAP Body has no wrapper: " + binding.wrapper());
        }
        return selected;
    }

    private List<List<Element>> fields(Element container, SoapBody binding) {
        Map<QName, Integer> indexes = new HashMap<>();
        List<List<Element>> fields = new ArrayList<>();
        for (SoapPart part : binding.parts()) {
            Integer previous = indexes.put(part.element(), fields.size());
            if (previous != null) {
                throw new IllegalArgumentException("Ambiguous SOAP part QName: " + part.element());
            }
            fields.add(new ArrayList<>());
        }
        for (Element element : this.elements(container)) {
            Integer index = indexes.get(this.name(element));
            if (index != null) {
                fields.get(index).add(element);
            } else {
                boolean reference = binding.encoded() && binding.wrapper() == null && this.referenceValue(element);
                if (!reference) {
                    throw new IllegalArgumentException("Unexpected SOAP part: " + this.name(element));
                }
            }
        }
        return fields;
    }

    private void requireTypes(SoapBody binding, Type[] types) {
        if (binding.parts().size() != types.length) {
            throw new IllegalArgumentException("SOAP part and Java type counts do not match");
        }
        for (SoapPart part : binding.parts()) {
            boolean invalidMaximum = part.maxOccurs() < -1;
            boolean invalidRange = part.maxOccurs() >= 0 && part.minOccurs() > part.maxOccurs();
            if (part.minOccurs() < 0 || invalidMaximum || invalidRange) {
                throw new IllegalArgumentException("Invalid SOAP part occurrence range: " + part.name());
            }
        }
    }

    private void validateCounts(SoapBody binding, int[] counts) {
        for (int index = 0; index < counts.length; index++) {
            SoapPart part = binding.parts().get(index);
            int count = counts[index];
            if (count < part.minOccurs()) {
                throw new IllegalArgumentException("SOAP message has too few occurrences of part: " + part.name());
            }
            if (part.maxOccurs() >= 0 && count > part.maxOccurs()) {
                throw new IllegalArgumentException("SOAP message has too many occurrences of part: " + part.name());
            }
        }
        for (SoapChoice choice : binding.choices()) {
            int selected = 0;
            for (int index : choice.partIndexes()) {
                if (counts[index] > 0) {
                    selected++;
                }
            }
            if (selected < choice.minOccurs() || selected > 1) {
                throw new IllegalArgumentException("SOAP choice must select " + choice.minOccurs() + " to 1 parts");
            }
        }
    }

    private List<?> items(SoapPart part, Type type, Object value) {
        if (!this.repeated(part)) {
            if (value == null && part.minOccurs() == 0) {
                return Collections.emptyList();
            }
            return Collections.singletonList(value);
        }
        this.collectionType(type);
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (!value.getClass().isArray()) {
            throw new IllegalArgumentException("Repeated SOAP part requires an array or collection: " + part.name());
        }
        List<Object> values = new ArrayList<>();
        for (int index = 0; index < Array.getLength(value); index++) {
            values.add(Array.get(value, index));
        }
        return values;
    }

    private JavaType collectionType(Type type) {
        JavaType collection = this.mapper.constructType(type);
        boolean array = collection.isArrayType();
        boolean iterable = Collection.class.isAssignableFrom(collection.getRawClass());
        JavaType content = collection.getContentType();
        if ((!array && !iterable) || content == null || content.hasRawClass(Object.class)) {
            throw new IllegalArgumentException("Repeated SOAP part requires an array or typed collection: " + type);
        }
        return collection;
    }

    @SuppressWarnings("unchecked")
    private Object collection(JavaType type, List<Object> values) {
        if (type.isArrayType()) {
            Object array = Array.newInstance(type.getContentType().getRawClass(), values.size());
            for (int index = 0; index < values.size(); index++) {
                Array.set(array, index, values.get(index));
            }
            return array;
        }
        Collection<Object> collection = this.mapper.convertValue(Collections.emptyList(), type);
        collection.addAll(values);
        return collection;
    }

    private boolean repeated(SoapPart part) {
        return part.maxOccurs() < 0 || part.maxOccurs() > 1;
    }

    private boolean choiceMember(SoapBody binding, int index) {
        return binding.choices().stream().anyMatch(choice -> choice.partIndexes().contains(index));
    }

    private boolean referenceValue(Element element) {
        if (this.version == SoapVersion.SOAP_11) {
            return !element.getAttribute("id").isEmpty();
        }
        String namespace = "http://www.w3.org/2003/05/soap-encoding";
        return !element.getAttributeNS(namespace, "id").isEmpty();
    }

    private List<Element> elements(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private QName name(Element element) {
        String localName = element.getLocalName();
        if (localName == null) {
            localName = element.getNodeName();
        }
        return new QName(element.getNamespaceURI(), localName);
    }

    private Element append(Element parent, QName name) {
        return SoapXmlUtils.append(parent, name.getNamespaceURI(), name.getLocalPart());
    }
}
