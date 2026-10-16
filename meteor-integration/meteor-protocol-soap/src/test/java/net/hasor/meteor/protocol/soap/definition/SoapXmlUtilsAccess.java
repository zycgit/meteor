/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import org.w3c.dom.Document;

/** 为单元测试提供 XML 复杂度检查的包内访问。 */
public final class SoapXmlUtilsAccess {
    private SoapXmlUtilsAccess() {
    }

    public static void validateDocument(Document document, int maxDepth, int maxNodes) {
        SoapXmlUtils.validateDocument(document, "Test XML", maxDepth, maxNodes);
    }
}
