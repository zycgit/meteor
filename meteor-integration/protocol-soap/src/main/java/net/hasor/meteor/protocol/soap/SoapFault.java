/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/** 服务可以抛出的 SOAP Fault，同时保留远端 Fault 的全部标准字段。 */
public final class SoapFault extends MetException {
    private final QName         code;
    private final List<QName>   subcodes   = new ArrayList<>();
    private final List<Reason>  reasons    = new ArrayList<>();
    private final List<Element> extensions = new ArrayList<>();
    private       String        node;
    private       String        role;
    private       String        actor;
    private       Element       detail;

    private SoapFault(short status, QName code, String message, List<Reason> reasons) {
        super(status, message);
        this.code = Objects.requireNonNull(code, "code");
        if (reasons == null || reasons.isEmpty()) {
            throw new IllegalArgumentException("SOAP Fault requires a reason");
        }
        for (Reason reason : reasons) {
            this.reasons.add(Objects.requireNonNull(reason, "reason"));
        }
    }

    /** 创建业务 Fault；SOAP 1.2 顶层 code 使用 SOAP 命名空间，应用代码放入 subcode。 */
    public static SoapFault create(QName code, String reason) {
        return new SoapFault(ProtocolStatus.InvokeError, code, reason, List.of(new Reason("en", reason)));
    }

    /** 使用 Meteor 协议状态创建 SOAP Fault，供协议适配器保留远端失败语义。 */
    public static SoapFault create(short status, QName code, String reason) {
        return new SoapFault(status, code, reason, List.of(new Reason("en", reason)));
    }

    /** 使用 Meteor 协议状态和线上 Reason 创建 SOAP Fault。 */
    public static SoapFault create(short status, QName code, String message, List<Reason> reasons) {
        return new SoapFault(status, code, message, reasons);
    }

    public QName getCode() {
        return this.code;
    }

    public List<QName> getSubcodes() {
        return List.copyOf(this.subcodes);
    }

    public SoapFault addSubcode(QName code) {
        this.subcodes.add(Objects.requireNonNull(code, "code"));
        return this;
    }

    public List<Reason> getReasons() {
        return List.copyOf(this.reasons);
    }

    public SoapFault addReason(String language, String text) {
        this.reasons.add(new Reason(language, Objects.requireNonNull(text, "text")));
        return this;
    }

    public String getNode() {
        return this.node;
    }

    public SoapFault node(String node) {
        this.node = node;
        return this;
    }

    public String getRole() {
        return this.role;
    }

    public SoapFault role(String role) {
        this.role = role;
        return this;
    }

    public String getActor() {
        return this.actor;
    }

    public SoapFault actor(String actor) {
        this.actor = actor;
        return this;
    }

    /** Detail 容器及其任意 XML 内容的独立副本。 */
    public Element getDetail() {
        return this.detail == null ? null : (Element) this.detail.cloneNode(true);
    }

    public SoapFault detail(Element detail) {
        this.detail = detail == null ? null : detached(detail);
        return this;
    }

    /** SOAP 1.1 允许额外的命名空间限定 Fault 子元素。 */
    public List<Element> getExtensions() {
        List<Element> copies = new ArrayList<>();
        for (Element extension : this.extensions) {
            copies.add((Element) extension.cloneNode(true));
        }
        return List.copyOf(copies);
    }

    public SoapFault addExtension(Element extension) {
        this.extensions.add(detached(Objects.requireNonNull(extension, "extension")));
        return this;
    }

    private static Element detached(Element source) {
        Element copy = (Element) source.cloneNode(true);
        Map<String, String> declarations = new LinkedHashMap<>();
        for (Node ancestor = source; ancestor instanceof Element; ancestor = ancestor.getParentNode()) {
            NamedNodeMap attributes = ancestor.getAttributes();
            for (int index = 0; index < attributes.getLength(); index++) {
                Attr attribute = (Attr) attributes.item(index);
                if (StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getNamespaceURI())) {
                    declarations.putIfAbsent(attribute.getName(), attribute.getValue());
                }
            }
        }
        declarations.forEach((name, value) -> copy.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, name, value));
        return copy;
    }

    /** SOAP 1.2 Reason/Text；语言相同的多条说明仍按线上顺序保留。 */
    public static final class Reason {
        private final String language;
        private final String text;

        public Reason(String language, String text) {
            this.language = language;
            this.text = Objects.requireNonNull(text, "text");
        }

        public String language() {
            return this.language;
        }

        public String text() {
            return this.text;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Reason that)) {
                return false;
            }
            return StringUtils.equals(this.language, that.language) && StringUtils.equals(this.text, that.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.language, this.text);
        }

        @Override
        public String toString() {
            return "Reason[language=" + this.language + ", text=" + this.text + "]";
        }
    }
}
