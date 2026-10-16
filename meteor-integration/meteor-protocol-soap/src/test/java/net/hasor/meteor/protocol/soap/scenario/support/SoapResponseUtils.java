/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.support;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** 从真实响应中读取精确 QName 对应的返回值，避免把 XML 排版当成协议契约。 */
public final class SoapResponseUtils {
    private SoapResponseUtils() {
    }

    public static String returnText(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList returns = document.getElementsByTagNameNS("", "return");
        if (returns.getLength() != 1) {
            throw new IllegalArgumentException("SOAP response requires one unqualified return element");
        }
        return returns.item(0).getTextContent();
    }
}
