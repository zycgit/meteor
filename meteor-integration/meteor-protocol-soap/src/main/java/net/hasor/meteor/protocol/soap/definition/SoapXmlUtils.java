/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import net.hasor.cobble.StringUtils;
import org.w3c.dom.*;

/** SOAP 定义生成、解析和结构校验使用的 DOM 文档操作。 */
public final class SoapXmlUtils {
    private static final int MAX_DEPTH = 128;
    private static final int MAX_NODES = 100_000;

    private SoapXmlUtils() {
    }

    public static Document newDocument() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException failure) {
            throw new IllegalStateException("Unable to create SOAP definition document", failure);
        }
    }

    public static Element append(Element parent, String namespace, String name) {
        Element child = parent.getOwnerDocument().createElementNS(namespace, name);
        parent.appendChild(child);
        return child;
    }

    public static void requireName(Document document, String name, String description) {
        if (StringUtils.isBlank(name) || name.indexOf(':') >= 0) {
            throw new IllegalArgumentException(description + " must be an XML local name: " + name);
        }
        try {
            document.createElementNS(null, name);
        } catch (DOMException failure) {
            throw new IllegalArgumentException(description + " must be an XML local name: " + name, failure);
        }
    }

    public static void validateDocument(Document document, String description) {
        SoapXmlUtils.validateDocument(document, description, MAX_DEPTH, MAX_NODES);
    }

    static void validateDocument(Document document, String description, int maxDepth, int maxNodes) {
        Deque<Node> nodes = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        nodes.push(document);
        depths.push(0);

        int count = 0;
        while (!nodes.isEmpty()) {
            Node node = nodes.pop();
            int depth = depths.pop();
            count++;
            NamedNodeMap attributes = node.getAttributes();
            if (attributes != null) {
                count += attributes.getLength();
            }
            if (count > maxNodes) {
                throw new IllegalArgumentException(description + " contains too many XML nodes");
            }
            if (depth > maxDepth) {
                throw new IllegalArgumentException(description + " exceeds XML nesting depth " + maxDepth);
            }

            NodeList children = node.getChildNodes();
            for (int index = children.getLength() - 1; index >= 0; index--) {
                Node child = children.item(index);
                int childDepth = child instanceof Element ? depth + 1 : depth;
                nodes.push(child);
                depths.push(childDepth);
            }
        }
    }
}
