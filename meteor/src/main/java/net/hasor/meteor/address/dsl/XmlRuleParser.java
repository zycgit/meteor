/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.dsl;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import net.hasor.cobble.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** XML 路由入口；更新阶段完成结构检查和表达式解析。 */
public final class XmlRuleParser {
    private static final Set<String> INPUTS = Set.of("env", "service", "method", "args", "addresses", "utils");

    public RouteRule parse(String xml) {
        Element root = readXml(xml);
        if (!StringUtils.equals(root.getTagName(), "route")) {
            throw new IllegalArgumentException("Route root must be <route>");
        }
        attributes(root, Set.of());
        List<RouteStep> steps = this.steps(root);
        if (root.getElementsByTagName("return").getLength() == 0) {
            throw new IllegalArgumentException("Route requires <return>");
        }
        return new RouteRule(steps);
    }

    private List<RouteStep> steps(Element parent) {
        List<RouteStep> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) {
                if (node.getNodeType() != Node.COMMENT_NODE && StringUtils.isNotBlank(node.getTextContent())) {
                    throw new IllegalArgumentException("Route steps must use XML elements");
                }
                continue;
            }

            RouteStep step = switch (element.getTagName()) {
                case "bind" -> {
                    attributes(element, Set.of("name", "value"));
                    yield new BindStep(variable(element, "name"), expression(element, "value"));
                }
                case "if" -> {
                    attributes(element, Set.of("test"));
                    yield new IfStep(expression(element, "test"), this.steps(element));
                }
                case "filter" -> {
                    attributes(element, Set.of("name", "from", "item", "test"));
                    String name = variable(element, "name");
                    String item = variable(element, "item");
                    yield new FilterStep(name, item, expression(element, "from"), expression(element, "test"));
                }
                case "return" -> {
                    attributes(element, Set.of("value"));
                    yield new ReturnStep(expression(element, "value"));
                }
                default -> throw new IllegalArgumentException("Unknown route step: " + element.getTagName());
            };

            if (!StringUtils.equals(element.getTagName(), "if") && element.hasChildNodes()) {
                for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child instanceof Element || StringUtils.isNotBlank(child.getTextContent())) {
                        throw new IllegalArgumentException("Only <if> may contain route steps");
                    }
                }
            }
            result.add(step);
        }
        return result;
    }

    private static RouteExpression expression(Element element, String name) {
        return new RouteExpression(required(element, name));
    }

    private static String variable(Element element, String attribute) {
        String name = required(element, attribute);
        if (INPUTS.contains(name) || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid route variable: " + name);
        }
        return name;
    }

    private static String required(Element element, String name) {
        String value = element.getAttribute(name);
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(element.getTagName() + " requires " + name);
        }
        return value;
    }

    private static void attributes(Element element, Set<String> allowed) {
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            String name = element.getAttributes().item(index).getNodeName();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown " + element.getTagName() + " attribute: " + name);
            }
        }
    }

    static Element readXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException failure) throws SAXParseException {
                    throw failure;
                }

                @Override
                public void fatalError(SAXParseException failure) throws SAXParseException {
                    throw failure;
                }
            });
            return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid rule XML", failure);
        }
    }
}