/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.soap.annotation.MetXmlType;
import net.hasor.meteor.protocol.soap.definition.SoapVersion;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlProperty;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** SOAP Encoding 对象图与 XML 表示之间的转换。 */
final class SoapEncodedValueCodec {
    private static final int            MAX_ARRAY_ITEMS  = 1_000_000;
    private static final String         SOAP_11_ENCODING = "http://schemas.xmlsoap.org/soap/encoding/";
    private static final String         SOAP_12_ENCODING = "http://www.w3.org/2003/05/soap-encoding";
    private static final String         XSI_NAMESPACE    = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String         XSD_NAMESPACE    = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final QName          ANY_TYPE         = new QName(XSD_NAMESPACE, "anyType");
    private static final Set<Class<?>>  SCALAR_TYPES;
    private final        SoapVersion    version;
    private final        XmlMapper      mapper;
    private final        SoapXmlMapping mapping;

    static {
        SCALAR_TYPES = Set.of(String.class, Character.class, char[].class, byte[].class, //
                Boolean.class, Byte.class, Short.class, Integer.class, Long.class, Float.class, //
                Double.class, BigInteger.class, BigDecimal.class, Date.class);
    }

    SoapEncodedValueCodec(SoapVersion version, XmlMapper mapper) {
        this.version = version;
        this.mapper = mapper;
        this.mapping = new SoapXmlMapping(mapper);
    }

    Encoder encoder(Document document, Element body) {
        return new Encoder(document, body);
    }

    Decoder decoder(Element body) {
        return new Decoder(body);
    }

    /** 一条 SOAP Body 共用的编码上下文。 */
    final class Encoder {
        private final Document                           document;
        private final Element                            body;
        private final IdentityHashMap<Object, GraphNode> references = new IdentityHashMap<>();
        private final Deque<GraphNode>                   pending    = new ArrayDeque<>();
        private       int                                nextId;

        private Encoder(Document document, Element body) {
            this.document = document;
            this.body = body;

            // 为后续类型和图属性准备稳定的命名空间前缀。
            SoapEncodedValueCodec.this.declare(body, XSI_NAMESPACE, "xsi");
            SoapEncodedValueCodec.this.declare(body, XSD_NAMESPACE, "xsd");
            SoapEncodedValueCodec.this.declare(body, SoapEncodedValueCodec.this.encodingNamespace(), "enc");
        }

        Element write(Element parent, QName name, QName declaredXmlType, //
                Type declaredJavaType, Object value) throws Exception {
            Element accessor = SoapEncodedValueCodec.this.element(this.document, parent, name);
            parent.appendChild(accessor);

            // SOAP 1.2 禁止在 Body 上声明 encodingStyle；裸 part 则在 accessor 上单独声明。
            Element encodingScope = parent == this.body ? accessor : parent;
            this.writeEncodingStyle(encodingScope);
            JavaType javaType = SoapEncodedValueCodec.this.mapper.constructType(declaredJavaType);
            this.writeAccessor(accessor, declaredXmlType, javaType, value);
            return accessor;
        }

        void finish() throws Exception {
            // 复杂值独立写入 Body；队列允许处理过程中继续发现循环或共享节点。
            while (!this.pending.isEmpty()) {
                GraphNode graphNode = this.pending.removeFirst();
                Element target = SoapEncodedValueCodec.this.element(this.document, this.body, new QName("multiRef"));
                this.writeId(target, graphNode.id());
                this.writeEncodingStyle(target);
                this.body.appendChild(target);
                this.writeGraphNode(target, graphNode);
            }
        }

        private void writeAccessor(Element accessor, QName declaredXmlType, //
                JavaType declaredJavaType, Object value) throws Exception {
            if (value == null) {
                this.writeNil(accessor, declaredXmlType);
                return;
            }

            JavaType valueType = SoapEncodedValueCodec.this.valueType(declaredJavaType, value);
            boolean scalar = SoapEncodedValueCodec.this.scalar(valueType);
            boolean referenceScalar = SoapEncodedValueCodec.this.referenceScalar(valueType);
            if (scalar && !referenceScalar) {
                SoapEncodedValueCodec.this.validateDeclaredXmlType(declaredXmlType, valueType);
                this.writeScalar(accessor, declaredXmlType, valueType, value);
                return;
            }

            GraphNode graphNode = this.references.get(value);
            if (graphNode == null) {
                String id = "id" + ++this.nextId;
                graphNode = new GraphNode(id, value, valueType, declaredXmlType);
                this.references.put(value, graphNode);
                if (SoapEncodedValueCodec.this.version == SoapVersion.SOAP_12) {
                    // SOAP 1.2 RPC Body 只能有一个调用结构，首次出现的节点直接内联到 accessor。
                    this.writeId(accessor, graphNode.id());
                    this.writeGraphNode(accessor, graphNode);
                    return;
                }
                this.pending.addLast(graphNode);
            } else if (graphNode.xmlType() == null && declaredXmlType != null) {
                graphNode.xmlType(declaredXmlType);
            }
            this.writeReference(accessor, graphNode.id());
        }

        private void writeGraphNode(Element target, GraphNode graphNode) throws Exception {
            if (SoapEncodedValueCodec.this.scalar(graphNode.javaType())) {
                if (SoapEncodedValueCodec.this.version == SoapVersion.SOAP_12) {
                    this.writeEncodingAttribute(target, "nodeType", "simple");
                }
                SoapEncodedValueCodec.this.validateDeclaredXmlType(graphNode.xmlType(), graphNode.javaType());
                this.writeScalar(target, graphNode.xmlType(), graphNode.javaType(), graphNode.value());
                return;
            }
            if (SoapEncodedValueCodec.this.array(graphNode.javaType())) {
                this.writeArray(target, graphNode);
                return;
            }
            this.writeStruct(target, graphNode);
        }

        private void writeArray(Element target, GraphNode graphNode) throws Exception {
            List<Object> values = SoapEncodedValueCodec.this.arrayValues(graphNode.value());
            JavaType contentType = SoapEncodedValueCodec.this.contentType(graphNode.javaType(), values);
            int[] dimensions = new int[] { values.size() };
            ArrayShape shape = SoapEncodedValueCodec.this.rectangularArray(graphNode.value(), graphNode.javaType());
            if (shape != null) {
                values = shape.values();
                contentType = shape.contentType();
                dimensions = shape.dimensions();
            }
            QName itemType = SoapEncodedValueCodec.this.xmlType(contentType);

            // SOAP 1.1 和 1.2 使用不同的数组描述属性。
            if (SoapEncodedValueCodec.this.version == SoapVersion.SOAP_11) {
                this.writeType(target, new QName(SoapEncodedValueCodec.this.encodingNamespace(), "Array", "enc"));
                JavaType descriptorType = contentType.isArrayType() ? //
                        SoapEncodedValueCodec.this.leafType(contentType) : contentType;
                QName descriptorXmlType = SoapEncodedValueCodec.this.xmlType(descriptorType);
                String arrayType = SoapEncodedValueCodec.this.soap11ArrayType(target, graphNode.javaType(), //
                        descriptorXmlType, dimensions);
                this.writeEncodingAttribute(target, "arrayType", arrayType);
            } else {
                this.writeEncodingAttribute(target, "nodeType", "array");
                this.writeEncodingAttribute(target, "itemType", SoapEncodedValueCodec.this.qName(target, itemType));
                String arraySize = SoapEncodedValueCodec.this.arrayDimensions(dimensions, " ");
                this.writeEncodingAttribute(target, "arraySize", arraySize);
            }

            for (Object value : values) {
                Element item = SoapEncodedValueCodec.this.element(this.document, target, new QName("item"));
                target.appendChild(item);
                this.writeAccessor(item, null, contentType, value);
            }
        }

        private void writeStruct(Element target, GraphNode graphNode) throws Exception {
            if (SoapEncodedValueCodec.this.version == SoapVersion.SOAP_12) {
                this.writeEncodingAttribute(target, "nodeType", "struct");
            }
            QName xmlType = graphNode.xmlType();
            if (xmlType == null) {
                xmlType = SoapEncodedValueCodec.this.namedXmlType(graphNode.javaType());
            }
            if (xmlType != null) {
                this.writeType(target, xmlType);
            }

            // 保留 Encoding 对象图，只将结构成员的 XML 映射交给共享元数据。
            SoapXmlType bean = SoapEncodedValueCodec.this.mapping.type(graphNode.javaType());
            for (SoapXmlProperty property : bean.getProperties()) {
                SoapEncodedValueCodec.this.requireEncodedProperty(property);
                Object propertyValue = property.read(graphNode.value());
                if (property.isAttribute()) {
                    if (propertyValue == null) {
                        SoapEncodedValueCodec.this.requireOptional(property);
                        continue;
                    }
                    QName name = property.getName();
                    String qualified = SoapEncodedValueCodec.this.qName(target, name);
                    String text = SoapEncodedValueCodec.this.scalarText(propertyValue);
                    target.setAttributeNS(name.getNamespaceURI(), qualified, text);
                    continue;
                }
                if (propertyValue == null && !property.isNillable()) {
                    SoapEncodedValueCodec.this.requireOptional(property);
                    continue;
                }
                Element propertyElement = SoapEncodedValueCodec.this.element(this.document, target, property.getName());
                target.appendChild(propertyElement);
                this.writeAccessor(propertyElement, null, property.getJavaType(), propertyValue);
            }
        }

        private void writeScalar(Element target, QName declaredXmlType, JavaType javaType, Object value) {
            QName xmlType = declaredXmlType;
            if (xmlType == null && javaType.isEnumType()) {
                // 无法确定生成类型的命名空间时，保留元素声明的枚举限制，不用基类 string 覆盖它。
                xmlType = SoapEncodedValueCodec.this.namedXmlType(javaType);
            } else if (xmlType == null) {
                xmlType = SoapEncodedValueCodec.this.xmlType(javaType);
            }
            if (xmlType != null) {
                this.writeType(target, xmlType);
            }
            target.setTextContent(SoapEncodedValueCodec.this.scalarText(value));
        }

        private void writeNil(Element target, QName declaredXmlType) {
            String xsiPrefix = SoapEncodedValueCodec.this.prefix(target, XSI_NAMESPACE, "xsi");
            target.setAttributeNS(XSI_NAMESPACE, xsiPrefix + ":nil", "true");
            if (declaredXmlType != null) {
                this.writeType(target, declaredXmlType);
            }
        }

        private void writeType(Element target, QName type) {
            String xsiPrefix = SoapEncodedValueCodec.this.prefix(target, XSI_NAMESPACE, "xsi");
            target.setAttributeNS(XSI_NAMESPACE, xsiPrefix + ":type", SoapEncodedValueCodec.this.qName(target, type));
        }

        private void writeId(Element target, String id) {
            if (SoapEncodedValueCodec.this.version == SoapVersion.SOAP_11) {
                target.setAttribute("id", id);
            } else {
                this.writeEncodingAttribute(target, "id", id);
            }
        }

        private void writeReference(Element target, String id) {
            if (SoapEncodedValueCodec.this.version == SoapVersion.SOAP_11) {
                target.setAttribute("href", "#" + id);
            } else {
                this.writeEncodingAttribute(target, "ref", id);
            }
        }

        private void writeEncodingAttribute(Element target, String name, String value) {
            String namespace = SoapEncodedValueCodec.this.encodingNamespace();
            String prefix = SoapEncodedValueCodec.this.prefix(target, namespace, "enc");
            target.setAttributeNS(namespace, prefix + ":" + name, value);
        }

        private void writeEncodingStyle(Element target) {
            String namespace = SoapEncodedValueCodec.this.version.namespace();
            if (target.hasAttributeNS(namespace, "encodingStyle")) {
                return;
            }
            String prefix = SoapEncodedValueCodec.this.prefix(target, namespace, "soap");
            target.setAttributeNS(namespace, prefix + ":encodingStyle", SoapEncodedValueCodec.this.encodingNamespace());
        }
    }

    /** 一条 SOAP Body 共用的解码上下文。 */
    final class Decoder {
        private final Map<String, Element> elements = new LinkedHashMap<>();
        private final Map<String, Object>  values   = new HashMap<>();

        private Decoder(Element body) {
            // 先建立整个编码作用域的索引，允许 accessor 引用后置节点。
            this.validateEncodingStyle(body);
            List<String> references = new ArrayList<>();
            this.index(body, references);
            for (String reference : references) {
                if (!this.elements.containsKey(reference)) {
                    throw new IllegalArgumentException("SOAP Encoding reference does not resolve: " + reference);
                }
            }
        }

        Object read(Element accessor, QName declaredXmlType, Type declaredJavaType) throws Exception {
            JavaType javaType = SoapEncodedValueCodec.this.mapper.constructType(declaredJavaType);
            return this.readAccessor(accessor, declaredXmlType, javaType);
        }

        private Object readAccessor(Element accessor, QName declaredXmlType, JavaType javaType) throws Exception {
            this.validateEncodingStyle(accessor);
            String reference = this.reference(accessor);
            Element valueElement = reference == null ? accessor : this.elements.get(reference);
            if (valueElement == null) {
                throw new IllegalArgumentException("SOAP Encoding reference does not resolve: " + reference);
            }
            this.validateEncodingStyle(valueElement);
            return this.readValue(valueElement, declaredXmlType, javaType);
        }

        private Object readValue(Element element, QName declaredXmlType, JavaType javaType) throws Exception {
            String id = this.id(element);
            if (id != null && this.values.containsKey(id)) {
                return this.values.get(id);
            }
            if (SoapEncodedValueCodec.this.nil(element)) {
                if (javaType.isPrimitive()) {
                    throw new IllegalArgumentException("SOAP Encoding nil cannot be assigned to " + javaType);
                }
                if (id != null) {
                    this.values.put(id, null);
                }
                return null;
            }
            String nodeType = this.nodeType(element);
            if (SoapEncodedValueCodec.this.scalar(javaType)) {
                if (StringUtils.isNotEmpty(nodeType) && !StringUtils.equals("simple", nodeType)) {
                    throw new IllegalArgumentException("SOAP Encoding node type does not match " + javaType);
                }
                if (this.arrayNode(element)) {
                    throw new IllegalArgumentException("SOAP Encoding array cannot map to " + javaType);
                }
                SoapEncodedValueCodec.this.validateXmlType(element, declaredXmlType, javaType);
                Object value = SoapEncodedValueCodec.this.scalarValue(element.getTextContent(), javaType);
                if (id != null) {
                    this.values.put(id, value);
                }
                return value;
            }
            if (SoapEncodedValueCodec.this.array(javaType) || this.arrayNode(element)) {
                if (!SoapEncodedValueCodec.this.array(javaType)) {
                    throw new IllegalArgumentException("SOAP Encoding array cannot map to " + javaType);
                }
                if (StringUtils.isNotEmpty(nodeType) && !StringUtils.equals("array", nodeType)) {
                    throw new IllegalArgumentException("SOAP Encoding node type does not match " + javaType);
                }
                return this.readArray(element, javaType, id);
            }
            if (StringUtils.isNotEmpty(nodeType) && !StringUtils.equals("struct", nodeType)) {
                throw new IllegalArgumentException("SOAP Encoding node type does not match " + javaType);
            }
            return this.readStruct(element, declaredXmlType, javaType, id);
        }

        private Object readArray(Element element, JavaType javaType, String id) throws Exception {
            List<Element> items = SoapEncodedValueCodec.this.children(element);
            ArrayLayout layout = this.arrayLayout(element, javaType, items);
            JavaType contentType = layout.contentType();
            Class<?> rawType = javaType.getRawClass();

            // 先缓存空容器，再读取成员，使数组或集合可以引用自身。
            if (rawType.isArray()) {
                Object array;
                if (layout.flat()) {
                    array = Array.newInstance(layout.contentType().getRawClass(), layout.dimensions());
                } else {
                    array = Array.newInstance(rawType.getComponentType(), layout.length());
                }
                if (id != null) {
                    this.values.put(id, array);
                }
                for (int index = 0; index < items.size(); index++) {
                    Object value = this.readAccessor(items.get(index), null, contentType);
                    int position = layout.positions()[index];
                    if (layout.flat()) {
                        SoapEncodedValueCodec.this.setArrayValue(array, layout.dimensions(), position, value);
                    } else {
                        Array.set(array, position, value);
                    }
                }
                return array;
            }

            Collection<Object> values = SoapEncodedValueCodec.this.newCollection(rawType);
            if (id != null) {
                this.values.put(id, values);
            }
            List<Object> ordered = new ArrayList<>(Collections.nCopies(layout.length(), null));
            for (int index = 0; index < items.size(); index++) {
                Object value = this.readAccessor(items.get(index), null, contentType);
                ordered.set(layout.positions()[index], value);
            }
            values.addAll(ordered);
            return values;
        }

        private ArrayLayout arrayLayout(Element element, JavaType javaType, List<Element> items) {
            if (SoapEncodedValueCodec.this.version == SoapVersion.SOAP_11) {
                return this.soap11ArrayLayout(element, javaType, items);
            }
            return this.soap12ArrayLayout(element, javaType, items);
        }

        private ArrayLayout soap11ArrayLayout(Element element, JavaType javaType, List<Element> items) {
            String encodedType = this.attribute(element, SoapEncodedValueCodec.this.encodingNamespace(), "arrayType", //
                    "SOAP 1.1 Encoding arrayType");
            Soap11ArrayType arrayType = SoapEncodedValueCodec.this.soap11ArrayType(element, encodedType, items.size());
            int declaredRank = SoapEncodedValueCodec.this.arrayRank(javaType.getRawClass());
            boolean flat = arrayType.dimensions().length > 1;
            if (flat && declaredRank != arrayType.dimensions().length) {
                throw new IllegalArgumentException("SOAP Encoding array dimensions do not match " + javaType);
            }
            if (!flat && arrayType.nestedDepth() != Math.max(0, declaredRank - 1)) {
                throw new IllegalArgumentException("SOAP Encoding nested array type does not match " + javaType);
            }

            JavaType contentType = flat ? SoapEncodedValueCodec.this.leafType(javaType) : //
                    SoapEncodedValueCodec.this.contentType(javaType, Collections.emptyList());
            JavaType wireType = flat || arrayType.nestedDepth() == 0 ? contentType : //
                    SoapEncodedValueCodec.this.leafType(contentType);
            SoapEncodedValueCodec.this.validateArrayItemType(arrayType.itemType(), wireType);
            int[] positions = this.soap11Positions(element, items, arrayType.dimensions());
            int length = SoapEncodedValueCodec.this.arrayLength(arrayType.dimensions());
            return new ArrayLayout(arrayType.dimensions(), positions, contentType, length, flat);
        }

        private ArrayLayout soap12ArrayLayout(Element element, JavaType javaType, List<Element> items) {
            String arraySize = this.attribute(element, SoapEncodedValueCodec.this.encodingNamespace(), "arraySize", //
                    "SOAP 1.2 Encoding arraySize");
            int[] dimensions = SoapEncodedValueCodec.this.soap12Dimensions(arraySize, items.size());
            int declaredRank = SoapEncodedValueCodec.this.arrayRank(javaType.getRawClass());
            boolean flat = dimensions.length > 1;
            if (flat && declaredRank != dimensions.length) {
                throw new IllegalArgumentException("SOAP Encoding array dimensions do not match " + javaType);
            }

            JavaType contentType = flat ? SoapEncodedValueCodec.this.leafType(javaType) : //
                    SoapEncodedValueCodec.this.contentType(javaType, Collections.emptyList());
            String encodingNamespace = SoapEncodedValueCodec.this.encodingNamespace();
            QName itemType = SoapEncodedValueCodec.this.attributeQName(element, encodingNamespace, //
                    "itemType");
            SoapEncodedValueCodec.this.validateArrayItemType(itemType, contentType);
            int length = SoapEncodedValueCodec.this.arrayLength(dimensions);
            if (items.size() != length) {
                throw new IllegalArgumentException("SOAP 1.2 Encoding array item count does not match arraySize");
            }
            int[] positions = new int[items.size()];
            for (int index = 0; index < positions.length; index++) {
                positions[index] = index;
            }
            return new ArrayLayout(dimensions, positions, contentType, length, flat);
        }

        private int[] soap11Positions(Element element, List<Element> items, int[] dimensions) {
            String offsetValue = this.attribute(element, SoapEncodedValueCodec.this.encodingNamespace(), "offset", //
                    "SOAP 1.1 Encoding offset");
            int[] offset = offsetValue == null ? new int[dimensions.length] : //
                    SoapEncodedValueCodec.this.coordinates(offsetValue, dimensions.length, "offset");
            int length = SoapEncodedValueCodec.this.arrayLength(dimensions);
            int[] positions = new int[items.size()];
            if (items.isEmpty()) {
                return positions;
            }
            int cursor = SoapEncodedValueCodec.this.arrayIndex(offset, dimensions);
            boolean[] assigned = new boolean[length];
            for (int index = 0; index < items.size(); index++) {
                Element item = items.get(index);
                String value = this.attribute(item, SoapEncodedValueCodec.this.encodingNamespace(), "position", //
                        "SOAP 1.1 Encoding position");
                if (value != null) {
                    int[] coordinates = SoapEncodedValueCodec.this.coordinates(value, dimensions.length, "position");
                    cursor = SoapEncodedValueCodec.this.arrayIndex(coordinates, dimensions);
                }
                if (cursor < 0 || cursor >= length) {
                    throw new IllegalArgumentException("SOAP 1.1 Encoding array position is outside arrayType");
                }
                if (assigned[cursor]) {
                    throw new IllegalArgumentException("Duplicate SOAP 1.1 Encoding array position: " + cursor);
                }
                assigned[cursor] = true;
                positions[index] = cursor++;
            }
            boolean dense = offsetValue == null && items.stream().noneMatch(item -> //
                    item.hasAttributeNS(SoapEncodedValueCodec.this.encodingNamespace(), "position"));
            if (dense && items.size() != length) {
                throw new IllegalArgumentException("SOAP 1.1 Encoding array item count does not match arrayType");
            }
            return positions;
        }

        private Object readStruct(Element element, QName declaredXmlType, //
                JavaType javaType, String id) throws Exception {
            if (declaredXmlType != null) {
                SoapEncodedValueCodec.this.validateXmlType(element, declaredXmlType, javaType);
            }
            SoapXmlType bean = SoapEncodedValueCodec.this.mapping.type(javaType);
            Object value = bean.newInstance();
            if (id != null) {
                this.values.put(id, value);
            }

            // 完整 QName 是结构成员身份，两个命名空间下的同名成员可以同时存在。
            Map<QName, Element> children = new LinkedHashMap<>();
            for (Element child : SoapEncodedValueCodec.this.children(element)) {
                String local = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
                QName name = new QName(child.getNamespaceURI(), local);
                if (children.put(name, child) != null) {
                    throw new IllegalArgumentException("Duplicate SOAP Encoding struct member: " + name);
                }
            }
            for (SoapXmlProperty property : bean.getProperties()) {
                SoapEncodedValueCodec.this.requireEncodedProperty(property);
                QName name = property.getName();
                if (property.isAttribute()) {
                    String namespace = StringUtils.isEmpty(name.getNamespaceURI()) ? null : name.getNamespaceURI();
                    if (!element.hasAttributeNS(namespace, name.getLocalPart())) {
                        SoapEncodedValueCodec.this.requireOptional(property);
                        continue;
                    }
                    String text = element.getAttributeNS(namespace, name.getLocalPart());
                    Object propertyValue = SoapEncodedValueCodec.this.scalarValue(text, property.getJavaType());
                    property.write(value, propertyValue);
                    continue;
                }
                Element child = children.get(name);
                if (child == null) {
                    SoapEncodedValueCodec.this.requireOptional(property);
                    continue;
                }
                if (!property.isNillable() && SoapEncodedValueCodec.this.nil(child)) {
                    throw new IllegalArgumentException("SOAP Encoding property is not nillable: " + name);
                }
                Object propertyValue = this.readAccessor(child, null, property.getJavaType());
                if (propertyValue == null && !property.isNillable()) {
                    throw new IllegalArgumentException("SOAP Encoding property is not nillable: " + name);
                }
                property.write(value, propertyValue);
            }
            return value;
        }

        private void index(Element element, List<String> references) {
            String id = this.id(element);
            String reference = this.reference(element);
            if (id != null && reference != null) {
                throw new IllegalArgumentException("SOAP Encoding element cannot contain both id and reference");
            }
            if (id != null && this.elements.put(id, element) != null) {
                throw new IllegalArgumentException("Duplicate SOAP Encoding id: " + id);
            }
            if (reference != null) {
                references.add(reference);
            }
            for (Element child : SoapEncodedValueCodec.this.children(element)) {
                this.index(child, references);
            }
        }

        private boolean arrayNode(Element element) {
            String nodeType = this.nodeType(element);
            if (StringUtils.equals("array", nodeType)) {
                return true;
            }
            QName type = SoapEncodedValueCodec.this.attributeQName(element, XSI_NAMESPACE, "type");
            QName arrayType = new QName(SoapEncodedValueCodec.this.encodingNamespace(), "Array");
            return arrayType.equals(type);
        }

        private String nodeType(Element element) {
            String value = element.getAttributeNS(SoapEncodedValueCodec.this.encodingNamespace(), "nodeType");
            if (StringUtils.isEmpty(value)) {
                return null;
            }
            boolean compound = StringUtils.equals("struct", value) || StringUtils.equals("array", value);
            boolean known = compound || StringUtils.equals("simple", value);
            if (!known) {
                throw new IllegalArgumentException("Unknown SOAP Encoding nodeType: " + value);
            }
            return value;
        }

        private void validateEncodingStyle(Element element) {
            Element scope = element;
            while (scope != null) {
                String namespace = SoapEncodedValueCodec.this.version.namespace();
                if (scope.hasAttributeNS(namespace, "encodingStyle")) {
                    String styles = scope.getAttributeNS(namespace, "encodingStyle").trim();
                    for (String style : styles.split("\\s+")) {
                        if (StringUtils.equals(SoapEncodedValueCodec.this.encodingNamespace(), style)) {
                            return;
                        }
                    }
                    throw new IllegalArgumentException("Unsupported SOAP encodingStyle: " + styles);
                }
                Node parent = scope.getParentNode();
                scope = parent instanceof Element parentElement ? parentElement : null;
            }
        }

        private String id(Element element) {
            if (SoapEncodedValueCodec.this.version == SoapVersion.SOAP_11) {
                return this.attribute(element, null, "id", "SOAP Encoding id");
            }
            return this.attribute(element, SoapEncodedValueCodec.this.encodingNamespace(), "id", "SOAP Encoding id");
        }

        private String reference(Element element) {
            String value;
            if (SoapEncodedValueCodec.this.version == SoapVersion.SOAP_11) {
                value = this.attribute(element, null, "href", "SOAP Encoding href");
                if (value == null) {
                    return null;
                }
                if (!StringUtils.startsWith(value, "#") || value.length() == 1) {
                    throw new IllegalArgumentException("SOAP Encoding href must be a local fragment: " + value);
                }
                return value.substring(1);
            }

            value = this.attribute(element, SoapEncodedValueCodec.this.encodingNamespace(), "ref", "SOAP Encoding ref");
            if (value != null && StringUtils.startsWith(value, "#")) {
                throw new IllegalArgumentException("SOAP 1.2 Encoding ref must be an IDREF: " + value);
            }
            return value;
        }

        private String attribute(Element element, String namespace, String name, String description) {
            boolean present = namespace == null ? element.hasAttribute(name) : element.hasAttributeNS(namespace, name);
            if (!present) {
                return null;
            }
            String value = namespace == null ? element.getAttribute(name) : element.getAttributeNS(namespace, name);
            if (StringUtils.isBlank(value)) {
                throw new IllegalArgumentException(description + " cannot be empty");
            }
            return value;
        }
    }

    private String encodingNamespace() {
        return this.version == SoapVersion.SOAP_11 ? SOAP_11_ENCODING : SOAP_12_ENCODING;
    }

    private JavaType valueType(JavaType declaredType, Object value) {
        Class<?> actualType = value.getClass();
        Class<?> declaredClass = declaredType.getRawClass();
        Class<?> assignableType = declaredClass.isPrimitive() ? this.wrapperType(declaredClass) : declaredClass;
        if (!assignableType.isAssignableFrom(actualType)) {
            String conversion = actualType.getName() + " cannot be assigned to " + declaredClass.getName();
            String message = "SOAP Encoding value " + conversion;
            throw new IllegalArgumentException(message);
        }
        if (assignableType == actualType) {
            return declaredType;
        }
        if (declaredType.isCollectionLikeType() || declaredType.isArrayType()) {
            return declaredType;
        }
        return this.mapper.getTypeFactory().constructSpecializedType(declaredType, actualType);
    }

    private Class<?> wrapperType(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private boolean scalar(JavaType type) {
        Class<?> rawType = type.getRawClass();
        return rawType.isPrimitive() || rawType.isEnum() || SCALAR_TYPES.contains(rawType);
    }

    private boolean array(JavaType type) {
        Class<?> rawType = type.getRawClass();
        boolean scalarArray = rawType == byte[].class || rawType == char[].class;
        return !scalarArray && (rawType.isArray() || Collection.class.isAssignableFrom(rawType));
    }

    private boolean referenceScalar(JavaType type) {
        Class<?> rawType = type.getRawClass();
        return rawType == byte[].class || rawType == char[].class;
    }

    private JavaType contentType(JavaType type, List<Object> values) {
        JavaType contentType = type.getContentType();
        if (contentType != null && contentType.getRawClass() != Object.class) {
            return contentType;
        }
        for (Object value : values) {
            if (value != null) {
                return this.mapper.constructType(value.getClass());
            }
        }
        return this.mapper.constructType(Object.class);
    }

    private JavaType leafType(JavaType type) {
        JavaType leaf = type;
        while (leaf.isArrayType()) {
            leaf = leaf.getContentType();
        }
        return leaf;
    }

    private int arrayRank(Class<?> type) {
        if (Collection.class.isAssignableFrom(type)) {
            return 1;
        }
        int rank = 0;
        Class<?> component = type;
        while (component.isArray() && component != byte[].class && component != char[].class) {
            rank++;
            component = component.getComponentType();
        }
        return rank;
    }

    private ArrayShape rectangularArray(Object value, JavaType javaType) {
        int rank = this.arrayRank(value.getClass());
        if (rank == 0) {
            return null;
        }
        int[] dimensions = new int[rank];
        Arrays.fill(dimensions, -1);
        List<Object> values = new ArrayList<>();
        if (!this.flattenArray(value, 0, dimensions, values)) {
            return null;
        }
        for (int index = 0; index < dimensions.length; index++) {
            if (dimensions[index] < 0) {
                dimensions[index] = 0;
            }
        }
        JavaType contentType = javaType;
        for (int depth = 0; depth < rank; depth++) {
            contentType = contentType.getContentType();
        }
        return new ArrayShape(dimensions, values, contentType);
    }

    private boolean flattenArray(Object array, int depth, int[] dimensions, List<Object> values) {
        if (array == null || !array.getClass().isArray()) {
            return false;
        }
        int length = Array.getLength(array);
        if (dimensions[depth] < 0) {
            dimensions[depth] = length;
        } else if (dimensions[depth] != length) {
            return false;
        }
        if (depth == dimensions.length - 1) {
            for (int index = 0; index < length; index++) {
                values.add(Array.get(array, index));
            }
            return true;
        }
        for (int index = 0; index < length; index++) {
            if (!this.flattenArray(Array.get(array, index), depth + 1, dimensions, values)) {
                return false;
            }
        }
        return true;
    }

    private String soap11ArrayType(Element scope, JavaType javaType, QName itemType, int[] dimensions) {
        StringBuilder value = new StringBuilder(this.qName(scope, itemType));
        if (dimensions.length == 1 && javaType.isArrayType() && javaType.getContentType().isArrayType()) {
            JavaType nested = javaType.getContentType();
            while (nested.isArrayType()) {
                value.append("[]");
                nested = nested.getContentType();
            }
        }
        value.append('[').append(this.arrayDimensions(dimensions, ",")).append(']');
        return value.toString();
    }

    private Soap11ArrayType soap11ArrayType(Element element, String value, int itemCount) {
        if (value == null) {
            return new Soap11ArrayType(null, new int[] { itemCount }, 0);
        }
        int dimensionStart = value.lastIndexOf('[');
        if (dimensionStart <= 0 || !value.endsWith("]")) {
            throw new IllegalArgumentException("Invalid SOAP 1.1 Encoding arrayType: " + value);
        }
        String typeName = value.substring(0, dimensionStart).trim();
        int nestedDepth = 0;
        while (typeName.endsWith("]")) {
            int nestedStart = typeName.lastIndexOf('[');
            if (nestedStart < 0) {
                break;
            }
            String nested = typeName.substring(nestedStart + 1, typeName.length() - 1);
            if (!this.dimensionMarker(nested)) {
                break;
            }
            nestedDepth += nested.length() + 1;
            typeName = typeName.substring(0, nestedStart).trim();
        }
        if (StringUtils.isEmpty(typeName)) {
            throw new IllegalArgumentException("Invalid SOAP 1.1 Encoding arrayType: " + value);
        }

        String dimensionText = value.substring(dimensionStart + 1, value.length() - 1).trim();
        int[] dimensions = StringUtils.isEmpty(dimensionText) ? new int[] { itemCount } : //
                this.concreteDimensions(dimensionText, ",", "SOAP 1.1 Encoding arrayType");
        QName itemType = this.lexicalQName(element, typeName, "SOAP 1.1 Encoding arrayType");
        return new Soap11ArrayType(itemType, dimensions, nestedDepth);
    }

    private boolean dimensionMarker(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != ',') {
                return false;
            }
        }
        return true;
    }

    private int[] soap12Dimensions(String value, int itemCount) {
        if (value == null || StringUtils.equals("*", value.trim())) {
            return new int[] { itemCount };
        }
        String normalized = value.trim();
        String[] values = normalized.split("\\s+");
        int[] dimensions = new int[values.length];
        int first = 0;
        if (StringUtils.equals("*", values[0])) {
            first = 1;
        }
        for (int index = first; index < values.length; index++) {
            if (StringUtils.equals("*", values[index])) {
                throw new IllegalArgumentException("SOAP 1.2 Encoding arraySize only allows '*' first");
            }
            dimensions[index] = this.nonNegativeInteger(values[index], "SOAP 1.2 Encoding arraySize");
        }
        if (first == 1) {
            int remainder = 1;
            for (int index = 1; index < dimensions.length; index++) {
                remainder = this.checkedProduct(remainder, dimensions[index]);
            }
            if (remainder == 0) {
                dimensions[0] = 0;
            } else if (itemCount % remainder == 0) {
                dimensions[0] = itemCount / remainder;
            } else {
                throw new IllegalArgumentException("SOAP 1.2 Encoding arraySize cannot infer its first dimension");
            }
        }
        return dimensions;
    }

    private int[] concreteDimensions(String value, String separator, String description) {
        String[] values = value.split(separator, -1);
        int[] dimensions = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            dimensions[index] = this.nonNegativeInteger(values[index].trim(), description);
        }
        return dimensions;
    }

    private int nonNegativeInteger(String value, String description) {
        if (StringUtils.isEmpty(value)) {
            throw new IllegalArgumentException("Invalid " + description + ": " + value);
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                throw new IllegalArgumentException("Invalid " + description + ": " + value);
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid " + description + ": " + value, failure);
        }
    }

    private String arrayDimensions(int[] dimensions, String separator) {
        StringJoiner joiner = new StringJoiner(separator);
        for (int dimension : dimensions) {
            joiner.add(Integer.toString(dimension));
        }
        return joiner.toString();
    }

    private int arrayLength(int[] dimensions) {
        int length = 1;
        for (int dimension : dimensions) {
            length = this.checkedProduct(length, dimension);
        }
        return length;
    }

    private int checkedProduct(int left, int right) {
        long value = (long) left * right;
        if (value > MAX_ARRAY_ITEMS) {
            throw new IllegalArgumentException("SOAP Encoding array exceeds " + MAX_ARRAY_ITEMS + " items");
        }
        return (int) value;
    }

    private int[] coordinates(String value, int rank, String description) {
        String normalized = value.trim();
        if (!normalized.startsWith("[") || !normalized.endsWith("]")) {
            throw new IllegalArgumentException("Invalid SOAP 1.1 Encoding " + description + ": " + value);
        }
        String content = normalized.substring(1, normalized.length() - 1);
        int[] coordinates = this.concreteDimensions(content, ",", "SOAP 1.1 Encoding " + description);
        if (coordinates.length != rank) {
            throw new IllegalArgumentException("SOAP 1.1 Encoding " + description + " rank does not match arrayType");
        }
        return coordinates;
    }

    private int arrayIndex(int[] coordinates, int[] dimensions) {
        int index = 0;
        for (int dimension = 0; dimension < dimensions.length; dimension++) {
            int coordinate = coordinates[dimension];
            if (coordinate < 0 || coordinate >= dimensions[dimension]) {
                throw new IllegalArgumentException("SOAP Encoding array coordinate is outside its dimensions");
            }
            index = this.checkedProduct(index, dimensions[dimension]) + coordinate;
        }
        return index;
    }

    private void setArrayValue(Object array, int[] dimensions, int index, Object value) {
        Object container = array;
        int remainder = index;
        for (int dimension = 0; dimension < dimensions.length - 1; dimension++) {
            int stride = 1;
            for (int nested = dimension + 1; nested < dimensions.length; nested++) {
                stride *= dimensions[nested];
            }
            int coordinate = stride == 0 ? 0 : remainder / stride;
            remainder = stride == 0 ? 0 : remainder % stride;
            container = Array.get(container, coordinate);
        }
        Array.set(container, remainder, value);
    }

    private void validateArrayItemType(QName wireType, JavaType javaType) {
        if (wireType == null || javaType.getRawClass() == Object.class) {
            return;
        }
        QName expected = this.xmlType(javaType);
        if (ANY_TYPE.equals(expected)) {
            return;
        }
        boolean generic = ANY_TYPE.equals(wireType);
        if (!generic && !expected.equals(wireType)) {
            throw new IllegalArgumentException("SOAP Encoding item type " + wireType + " cannot map to " + javaType);
        }
    }

    private void validateXmlType(Element element, QName declaredType, JavaType javaType) {
        QName wireType = this.attributeQName(element, XSI_NAMESPACE, "type");
        if (wireType == null) {
            return;
        }
        QName expected = declaredType == null ? this.xmlType(javaType) : declaredType;
        boolean generic = ANY_TYPE.equals(wireType);
        if (!generic && !expected.equals(wireType)) {
            throw new IllegalArgumentException("SOAP Encoding type " + wireType + " cannot map to " + javaType);
        }
    }

    private void validateDeclaredXmlType(QName declaredType, JavaType javaType) {
        if (declaredType == null || !StringUtils.equals(XSD_NAMESPACE, declaredType.getNamespaceURI())) {
            return;
        }
        QName expected = this.baseXmlType(javaType);
        boolean generic = StringUtils.equals("anyType", declaredType.getLocalPart());
        if (!generic && !expected.equals(declaredType)) {
            throw new IllegalArgumentException("SOAP Encoding type " + declaredType + " cannot map to " + javaType);
        }
    }

    private QName xmlType(JavaType type) {
        QName named = this.namedXmlType(type);
        return named == null ? this.baseXmlType(type) : named;
    }

    private QName namedXmlType(JavaType type) {
        if (!type.getRawClass().isAnnotationPresent(MetXmlType.class)) {
            return null;
        }
        SoapXmlType xml = this.mapping.type(type);
        if (StringUtils.isEmpty(xml.getName()) || StringUtils.isEmpty(xml.getNamespace())) {
            return null;
        }
        return new QName(xml.getNamespace(), xml.getName());
    }

    private QName baseXmlType(JavaType type) {
        Class<?> rawType = type.getRawClass();
        String localName;
        boolean character = rawType == Character.class || rawType == char.class || rawType == char[].class;
        if (rawType == String.class || character || rawType.isEnum()) {
            localName = "string";
        } else if (rawType == Boolean.class || rawType == boolean.class) {
            localName = "boolean";
        } else if (rawType == Byte.class || rawType == byte.class) {
            localName = "byte";
        } else if (rawType == Short.class || rawType == short.class) {
            localName = "short";
        } else if (rawType == Integer.class || rawType == int.class) {
            localName = "int";
        } else if (rawType == Long.class || rawType == long.class || rawType == Date.class) {
            localName = "long";
        } else if (rawType == Float.class || rawType == float.class) {
            localName = "float";
        } else if (rawType == Double.class || rawType == double.class) {
            localName = "double";
        } else if (rawType == BigInteger.class) {
            localName = "integer";
        } else if (rawType == BigDecimal.class) {
            localName = "decimal";
        } else if (rawType == byte[].class) {
            localName = "base64Binary";
        } else {
            localName = "anyType";
        }
        return new QName(XSD_NAMESPACE, localName, "xsd");
    }

    private String scalarText(Object value) {
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof char[] characters) {
            return new String(characters);
        }
        if (value instanceof Date date) {
            return Long.toString(date.getTime());
        }
        if (value instanceof Float floating) {
            if (floating.isNaN()) {
                return "NaN";
            }
            if (floating == Float.POSITIVE_INFINITY) {
                return "INF";
            }
            if (floating == Float.NEGATIVE_INFINITY) {
                return "-INF";
            }
            return floating.toString();
        }
        if (value instanceof Double floating) {
            return this.floatingText(floating);
        }
        if (value instanceof Enum<?> enumeration) {
            return this.enumText(enumeration);
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }

    private String floatingText(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "INF";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-INF";
        }
        return Double.toString(value);
    }

    private Object scalarValue(String text, JavaType javaType) {
        Class<?> rawType = javaType.getRawClass();
        if (rawType == String.class) {
            return text;
        }
        if (rawType == Character.class || rawType == char.class) {
            if (text.length() != 1) {
                throw new IllegalArgumentException("SOAP Encoding character must contain exactly one character");
            }
            return text.charAt(0);
        }
        if (rawType == char[].class) {
            return text.toCharArray();
        }
        if (rawType == byte[].class) {
            return Base64.getDecoder().decode(text.replaceAll("[ \t\r\n]", ""));
        }
        String normalized = text.trim();
        if (rawType == Boolean.class || rawType == boolean.class) {
            if (StringUtils.equals("true", normalized) || StringUtils.equals("1", normalized)) {
                return true;
            }
            if (StringUtils.equals("false", normalized) || StringUtils.equals("0", normalized)) {
                return false;
            }
            throw new IllegalArgumentException("Invalid XML Schema boolean: " + text);
        }
        if (rawType == Byte.class || rawType == byte.class) {
            return Byte.valueOf(normalized);
        }
        if (rawType == Short.class || rawType == short.class) {
            return Short.valueOf(normalized);
        }
        if (rawType == Integer.class || rawType == int.class) {
            return Integer.valueOf(normalized);
        }
        if (rawType == Long.class || rawType == long.class) {
            return Long.valueOf(normalized);
        }
        if (rawType == Float.class || rawType == float.class) {
            return Float.valueOf(this.javaFloatingText(normalized));
        }
        if (rawType == Double.class || rawType == double.class) {
            return Double.valueOf(this.javaFloatingText(normalized));
        }
        if (rawType == BigInteger.class) {
            return new BigInteger(normalized);
        }
        if (rawType == BigDecimal.class) {
            return new BigDecimal(normalized);
        }
        if (rawType == Date.class) {
            return new Date(Long.parseLong(normalized));
        }
        if (rawType.isEnum()) {
            return this.enumValue(rawType, text);
        }
        throw this.unsupported(javaType, "not a supported scalar");
    }

    private String javaFloatingText(String value) {
        if (StringUtils.equals("INF", value)) {
            return "Infinity";
        }
        if (StringUtils.equals("-INF", value)) {
            return "-Infinity";
        }
        return value;
    }

    private String enumText(Enum<?> value) {
        return this.mapping.enumValue(value);
    }

    private Object enumValue(Class<?> enumType, String value) {
        return this.mapping.enumValue(enumType, value);
    }

    private void requireEncodedProperty(SoapXmlProperty property) {
        if (property.isText() || property.getWrapper() != null) {
            String reason = "SOAP Encoding does not support XML text or collection wrapper properties";
            throw new IllegalArgumentException(reason);
        }
        QName name = property.getName();
        if (property.isAttribute() && this.version.isEncodingControlAttribute(name)) {
            throw new IllegalArgumentException("SOAP Encoding XML attribute uses a reserved graph name: " + name);
        }
    }

    private void requireOptional(SoapXmlProperty property) {
        if (property.isRequired()) {
            throw new IllegalArgumentException("Required SOAP Encoding property has no value: " + property.getName());
        }
    }

    private List<Object> arrayValues(Object value) {
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        int length = Array.getLength(value);
        List<Object> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            values.add(Array.get(value, index));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Collection<Object> newCollection(Class<?> type) throws Exception {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            if (Set.class.isAssignableFrom(type)) {
                return new LinkedHashSet<>();
            }
            if (Queue.class.isAssignableFrom(type)) {
                return new LinkedList<>();
            }
            return new ArrayList<>();
        }
        return (Collection<Object>) this.newObject(type, true);
    }

    private Object newObject(Class<?> type, boolean allowJavaType) throws Exception {
        boolean javaType = type.getName().startsWith("java.");
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers()) || javaType && !allowJavaType) {
            throw new IllegalArgumentException("SOAP Encoding cannot create " + type.getName());
        }
        Constructor<?> constructor = type.getDeclaredConstructor();
        if (!constructor.trySetAccessible()) {
            throw new IllegalArgumentException("SOAP Encoding cannot access the constructor of " + type.getName());
        }
        return constructor.newInstance();
    }

    private boolean nil(Element element) {
        String nil = element.getAttributeNS(XSI_NAMESPACE, "nil");
        String legacyNull = element.getAttributeNS(XSI_NAMESPACE, "null");
        boolean currentNil = StringUtils.equalsIgnoreCase("true", nil) || StringUtils.equals("1", nil);
        boolean legacyNil = StringUtils.equalsIgnoreCase("true", legacyNull) || StringUtils.equals("1", legacyNull);
        return currentNil || legacyNil;
    }

    private Element element(Document document, Element namespaceScope, QName name) {
        if (StringUtils.isEmpty(name.getNamespaceURI())) {
            Element element = document.createElementNS(null, name.getLocalPart());
            if (namespaceScope.lookupNamespaceURI(null) != null) {
                element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", "");
            }
            return element;
        }
        String preferred = StringUtils.isEmpty(name.getPrefix()) ? "ns" : name.getPrefix();
        String prefix = this.prefix(namespaceScope, name.getNamespaceURI(), preferred);
        return document.createElementNS(name.getNamespaceURI(), prefix + ":" + name.getLocalPart());
    }

    private String qName(Element scope, QName name) {
        if (StringUtils.isEmpty(name.getNamespaceURI())) {
            return name.getLocalPart();
        }
        String preferred = StringUtils.isEmpty(name.getPrefix()) ? "ns" : name.getPrefix();
        String prefix = this.prefix(scope, name.getNamespaceURI(), preferred);
        return prefix + ":" + name.getLocalPart();
    }

    private QName attributeQName(Element element, String namespace, String localName) {
        if (!element.hasAttributeNS(namespace, localName)) {
            return null;
        }
        String value = element.getAttributeNS(namespace, localName);
        return this.lexicalQName(element, value, "SOAP Encoding QName");
    }

    private QName lexicalQName(Element element, String value, String description) {
        String normalized = value.trim();
        if (StringUtils.isEmpty(normalized) || normalized.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("Invalid " + description + ": " + value);
        }
        int separator = normalized.indexOf(':');
        if (separator < 0) {
            String defaultNamespace = element.lookupNamespaceURI(null);
            return new QName(defaultNamespace == null ? "" : defaultNamespace, normalized);
        }
        if (separator == 0 || separator == normalized.length() - 1 || normalized.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("Invalid " + description + ": " + value);
        }
        String prefix = normalized.substring(0, separator);
        String typeNamespace = element.lookupNamespaceURI(prefix);
        if (typeNamespace == null) {
            throw new IllegalArgumentException("SOAP Encoding QName uses an undeclared prefix: " + value);
        }
        return new QName(typeNamespace, normalized.substring(separator + 1), prefix);
    }

    private String prefix(Element scope, String namespace, String preferred) {
        if (StringUtils.equals(XMLConstants.XML_NS_URI, namespace)) {
            return XMLConstants.XML_NS_PREFIX;
        }
        String existing = scope.lookupPrefix(namespace);
        if (StringUtils.isNotEmpty(existing)) {
            return existing;
        }
        Element declaration = scope.getOwnerDocument().getDocumentElement();
        if (declaration == null) {
            declaration = scope;
        }
        String prefix = preferred;
        int sequence = 1;
        String boundNamespace = declaration.lookupNamespaceURI(prefix);
        while (boundNamespace != null && !StringUtils.equals(namespace, boundNamespace)) {
            prefix = preferred + sequence++;
            boundNamespace = declaration.lookupNamespaceURI(prefix);
        }
        declaration.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + prefix, namespace);
        return prefix;
    }

    private void declare(Element scope, String namespace, String preferred) {
        this.prefix(scope, namespace, preferred);
    }

    private List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private IllegalArgumentException unsupported(JavaType type, String reason) {
        return new IllegalArgumentException("Cannot apply SOAP Encoding to " + type.toCanonical() + ": " + reason);
    }

    /** 已解析的数组布局。 */
    private static final class ArrayLayout {
        private final int[]    dimensions;
        private final int[]    positions;
        private final JavaType contentType;
        private final int      length;
        private final boolean  flat;

        private ArrayLayout(int[] dimensions, int[] positions, JavaType contentType, int length, boolean flat) {
            this.dimensions = dimensions;
            this.positions = positions;
            this.contentType = contentType;
            this.length = length;
            this.flat = flat;
        }

        private int[] dimensions() {
            return this.dimensions;
        }

        private int[] positions() {
            return this.positions;
        }

        private JavaType contentType() {
            return this.contentType;
        }

        private int length() {
            return this.length;
        }

        private boolean flat() {
            return this.flat;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ArrayLayout other)) {
                return false;
            }
            return this.dimensions == other.dimensions && this.positions == other.positions && //
                    Objects.equals(this.contentType, other.contentType) && this.length == other.length && //
                    this.flat == other.flat;
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(this.dimensions);
            result = 31 * result + System.identityHashCode(this.positions);
            result = 31 * result + Objects.hashCode(this.contentType);
            result = 31 * result + Integer.hashCode(this.length);
            result = 31 * result + Boolean.hashCode(this.flat);
            return result;
        }

        @Override
        public String toString() {
            return "ArrayLayout[dimensions=" + this.dimensions + ", positions=" + this.positions + //
                    ", contentType=" + this.contentType + ", length=" + this.length + ", flat=" + this.flat + "]";
        }
    }

    /** Java 矩形数组的维度和展平值。 */
    private static final class ArrayShape {
        private final int[]        dimensions;
        private final List<Object> values;
        private final JavaType     contentType;

        private ArrayShape(int[] dimensions, List<Object> values, JavaType contentType) {
            this.dimensions = dimensions;
            this.values = values;
            this.contentType = contentType;
        }

        private int[] dimensions() {
            return this.dimensions;
        }

        private List<Object> values() {
            return this.values;
        }

        private JavaType contentType() {
            return this.contentType;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ArrayShape other)) {
                return false;
            }
            return this.dimensions == other.dimensions && Objects.equals(this.values, other.values) && //
                    Objects.equals(this.contentType, other.contentType);
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(this.dimensions);
            result = 31 * result + Objects.hashCode(this.values);
            result = 31 * result + Objects.hashCode(this.contentType);
            return result;
        }

        @Override
        public String toString() {
            return "ArrayShape[dimensions=" + this.dimensions + ", values=" + this.values + //
                    ", contentType=" + this.contentType + "]";
        }
    }

    /** SOAP 1.1 arrayType 的项类型、当前维度与嵌套深度。 */
    private static final class Soap11ArrayType {
        private final QName itemType;
        private final int[] dimensions;
        private final int   nestedDepth;

        private Soap11ArrayType(QName itemType, int[] dimensions, int nestedDepth) {
            this.itemType = itemType;
            this.dimensions = dimensions;
            this.nestedDepth = nestedDepth;
        }

        private QName itemType() {
            return this.itemType;
        }

        private int[] dimensions() {
            return this.dimensions;
        }

        private int nestedDepth() {
            return this.nestedDepth;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Soap11ArrayType other)) {
                return false;
            }
            return Objects.equals(this.itemType, other.itemType) && this.dimensions == other.dimensions && //
                    this.nestedDepth == other.nestedDepth;
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(this.itemType);
            result = 31 * result + System.identityHashCode(this.dimensions);
            result = 31 * result + Integer.hashCode(this.nestedDepth);
            return result;
        }

        @Override
        public String toString() {
            return "Soap11ArrayType[itemType=" + this.itemType + ", dimensions=" + this.dimensions + //
                    ", nestedDepth=" + this.nestedDepth + "]";
        }
    }

    /** 编码队列中的对象图节点。 */
    private static final class GraphNode {
        private final String   id;
        private final Object   value;
        private final JavaType javaType;
        private       QName    xmlType;

        private GraphNode(String id, Object value, JavaType javaType, QName xmlType) {
            this.id = id;
            this.value = value;
            this.javaType = javaType;
            this.xmlType = xmlType;
        }

        private String id() {
            return this.id;
        }

        private Object value() {
            return this.value;
        }

        private JavaType javaType() {
            return this.javaType;
        }

        private QName xmlType() {
            return this.xmlType;
        }

        private void xmlType(QName xmlType) {
            this.xmlType = xmlType;
        }
    }
}
