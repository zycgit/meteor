/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.rsf.address;
import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.rsf.address.route.Rule;
import net.hasor.rsf.address.route.RuleParser;
import net.hasor.rsf.address.route.random.RandomFlowControl;
import net.hasor.rsf.address.route.speed.SpeedFlowControl;
import net.hasor.rsf.address.route.unit.UnitFlowControl;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * 方便引用切换。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2014年9月12日
 */
public class FlowControlRef {
    protected final      Logger            logger            = LoggerFactory.getLogger(getClass());
    private static final RuleParser        ruleParser        = new RuleParser();
    public               String            flowControlScript = null;
    public               UnitFlowControl   unitFlowControl   = null; //单元规则
    public               RandomFlowControl randomFlowControl = null; //地址选取规则
    public               SpeedFlowControl  speedFlowControl  = null; //QoS速率规则

    private FlowControlRef() {
    }

    /** Apply a complete controlSet. An empty set restores defaults; invalid input leaves it unchanged. */
    public void updateFlowControl(String flowControl) {
        tryUpdateFlowControl(flowControl);
    }

    boolean tryUpdateFlowControl(String flowControl) {
        if (StringUtils.isBlank(flowControl)) {
            return false;
        }
        FlowControlRef parsed = defaultRef();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Element root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(flowControl))).getDocumentElement();
            if (!"controlSet".equals(root.getTagName())) {
                return false;
            }

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element)) {
                    continue;
                }
                if (!"flowControl".equals(node.getNodeName())) {
                    return false;
                }

                StringWriter xml = new StringWriter();
                transformer.transform(new DOMSource(node), new StreamResult(xml));
                Rule rule = ruleParser.ruleSettings(xml.toString());
                if (rule == null) {
                    return false;
                }

                if (rule instanceof UnitFlowControl) {
                    parsed.unitFlowControl = (UnitFlowControl) rule;
                } else if (rule instanceof RandomFlowControl) {
                    parsed.randomFlowControl = (RandomFlowControl) rule;
                } else if (rule instanceof SpeedFlowControl) {
                    parsed.speedFlowControl = (SpeedFlowControl) rule;
                }
            }
        } catch (Exception e) {
            logger.error("Invalid flow control: " + e.getMessage(), e);
            return false;
        }

        this.unitFlowControl = parsed.unitFlowControl;
        this.randomFlowControl = parsed.randomFlowControl;
        this.speedFlowControl = parsed.speedFlowControl;
        this.flowControlScript = flowControl.trim();
        return true;
    }

    public static FlowControlRef newRef(FlowControlRef ref) {
        FlowControlRef newRef = defaultRef();
        if (!StringUtils.isBlank(ref.flowControlScript)) {
            newRef.flowControlScript = ref.flowControlScript;
        }
        if (ref.unitFlowControl != null) {
            newRef.unitFlowControl = ref.unitFlowControl;
        }
        if (ref.randomFlowControl != null) {
            newRef.randomFlowControl = ref.randomFlowControl;
        }
        if (ref.speedFlowControl != null) {
            newRef.speedFlowControl = ref.speedFlowControl;
        }
        return newRef;
    }

    public static FlowControlRef defaultRef() {
        FlowControlRef flowControlRef = new FlowControlRef();
        flowControlRef.randomFlowControl = new RandomFlowControl();
        flowControlRef.speedFlowControl = SpeedFlowControl.defaultControl();
        return flowControlRef;
    }
}