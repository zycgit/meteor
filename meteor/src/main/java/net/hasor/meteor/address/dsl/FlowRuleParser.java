/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.dsl;
import java.io.StringWriter;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.address.FlowControlRef;
import net.hasor.meteor.address.route.Rule;
import net.hasor.meteor.address.route.RuleParser;
import net.hasor.meteor.address.route.random.RandomFlowControl;
import net.hasor.meteor.address.route.speed.SpeedFlowControl;
import net.hasor.meteor.address.route.unit.UnitFlowControl;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 固定格式的 XML 流控配置，解析后复用 Java 流控对象。 */
public final class FlowRuleParser {
    private final RuleParser ruleParser = new RuleParser();

    public FlowControlRef parse(String script) {
        FlowControlRef parsed = FlowControlRef.defaultRef();
        try {
            Element root = XmlRuleParser.readXml(script);
            if (!StringUtils.equals("controlSet", root.getTagName())) {
                throw new IllegalArgumentException("Invalid XML flow control");
            }

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element)) {
                    continue;
                }
                if (!StringUtils.equals("flowControl", node.getNodeName())) {
                    throw new IllegalArgumentException("Invalid XML flow control");
                }

                StringWriter xml = new StringWriter();
                transformer.transform(new DOMSource(node), new StreamResult(xml));
                Rule rule = this.ruleParser.ruleSettings(xml.toString());
                if (rule == null) {
                    throw new IllegalArgumentException("Invalid XML flow control");
                }

                if (rule instanceof UnitFlowControl) {
                    parsed.unitFlowControl = (UnitFlowControl) rule;
                } else if (rule instanceof RandomFlowControl) {
                    parsed.randomFlowControl = (RandomFlowControl) rule;
                } else if (rule instanceof SpeedFlowControl) {
                    parsed.speedFlowControl = (SpeedFlowControl) rule;
                }
            }
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid XML flow control", failure);
        }
        parsed.flowControlScript = script;
        return parsed;
    }
}
