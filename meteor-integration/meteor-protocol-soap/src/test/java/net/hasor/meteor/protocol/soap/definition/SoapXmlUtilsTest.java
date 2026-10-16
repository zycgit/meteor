/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SoapXmlUtilsTest {
    @Test
    public void elementDepthOverLimitIsRejected() throws Exception {
        // 构造深度超过限制的 DOM，不经过递归生成测试数据。
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element current = document.createElement("root");
        document.appendChild(current);
        for (int index = 0; index < 4; index++) {
            Element child = document.createElement("level" + index);
            current.appendChild(child);
            current = child;
        }

        // 迭代检查在进入 XML 转换前拒绝过深结构。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapXmlUtils.validateDocument(document, "Test XML", 4, 100);
        });
        assertEquals("Test XML exceeds XML nesting depth 4", failure.getMessage());
    }

    @Test
    public void totalNodesOverLimitIsRejected() throws Exception {
        // 构造宽度超过限制的 DOM，节点数量包含 Document 和元素。
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element root = document.createElement("root");
        document.appendChild(root);
        for (int index = 0; index < 8; index++) {
            root.appendChild(document.createElement("item" + index));
        }

        // 节点总量超限时立即停止遍历。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            SoapXmlUtils.validateDocument(document, "Test XML", 8, 5);
        });
        assertEquals("Test XML contains too many XML nodes", failure.getMessage());
    }
}
