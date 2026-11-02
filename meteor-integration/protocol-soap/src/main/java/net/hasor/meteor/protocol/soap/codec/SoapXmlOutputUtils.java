/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.XMLFilterImpl;

/** 输出 SOAP 消息，保留业务属性的映射顺序，不依赖 DOM 属性表的遍历顺序。 */
final class SoapXmlOutputUtils {
    private static final String ATTRIBUTE_ORDER = SoapXmlOutputUtils.class.getName() + ".attributeOrder";

    private SoapXmlOutputUtils() {
    }

    static void attribute(Element target, QName name, String qualified, String value) {
        target.setAttributeNS(name.getNamespaceURI(), qualified, value);
        List<QName> order = attributeOrder(target);
        if (order == null) {
            order = new ArrayList<>();
            target.setUserData(ATTRIBUTE_ORDER, order, SoapXmlOutputUtils::copyAttributeOrder);
        }

        // 业务属性按映射顺序逐项登记；重复赋值不改变原位置。
        if (!order.contains(name)) {
            order.add(name);
        }
    }

    static void write(Document document, OutputStream output) throws Exception {
        SAXTransformerFactory factory = (SAXTransformerFactory) TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        TransformerHandler handler = factory.newTransformerHandler();
        Transformer serializer = handler.getTransformer();
        serializer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        serializer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        handler.setResult(new StreamResult(output));

        NodeList elements = document.getElementsByTagName("*");
        XMLFilterImpl filter = new XMLFilterImpl() {
            private int index;

            @Override
            public void startElement(String uri, String localName, String qualified, Attributes attributes) //
                    throws SAXException {
                // identity Transformer 按 DOM 前序输出元素，顺序与此 NodeList 一致。
                Element element = (Element) elements.item(this.index++);
                List<QName> order = attributeOrder(element);
                super.startElement(uri, localName, qualified, order == null //
                        ? attributes : orderedAttributes(attributes, order));
            }
        };
        filter.setContentHandler(handler);
        SAXResult result = new SAXResult(filter);
        result.setLexicalHandler(handler);
        // DOM identity 转换继续负责命名空间修复、文本转义、CDATA、注释和处理指令。
        factory.newTransformer().transform(new DOMSource(document), result);
    }

    private static Attributes orderedAttributes(Attributes source, List<QName> order) {
        AttributesImpl result = new AttributesImpl();
        for (int index = 0; index < source.getLength(); index++) {
            QName name = new QName(source.getURI(index), source.getLocalName(index));
            if (!order.contains(name)) {
                addAttribute(result, source, index);
            }
        }

        // 协议和命名空间属性保留，业务属性按 Jackson 属性顺序 排列在其后。
        for (QName name : order) {
            int index = source.getIndex(name.getNamespaceURI(), name.getLocalPart());
            if (index >= 0) {
                addAttribute(result, source, index);
            }
        }
        return result;
    }

    private static void addAttribute(AttributesImpl target, Attributes source, int index) {
        target.addAttribute(source.getURI(index), source.getLocalName(index), //
                source.getQName(index), source.getType(index), source.getValue(index));
    }

    @SuppressWarnings("unchecked")
    private static List<QName> attributeOrder(Node node) {
        return (List<QName>) node.getUserData(ATTRIBUTE_ORDER);
    }

    private static void copyAttributeOrder(short operation, String key, Object data, Node source, Node destination) {
        if (destination != null) {
            // MTOM/SWA 会克隆消息；副本持有独立的顺序列表，后续增删属性不影响原文档。
            destination.setUserData(key, new ArrayList<>(attributeOrder(source)), SoapXmlOutputUtils::copyAttributeOrder);
        }
    }
}
