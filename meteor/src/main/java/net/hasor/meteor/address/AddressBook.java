/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import net.hasor.cobble.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** 消费端静态地址本的 XML 解析结果；不持有 XML 节点或运行中的服务。 */
public final class AddressBook {
    private final List<Service> services;

    private AddressBook(List<Service> services) {
        this.services = List.copyOf(services);
    }

    public List<Service> getServices() {
        return this.services;
    }

    /** 使用内置 XSD/DTD 离线校验；输入流由调用方关闭。 */
    public static AddressBook parse(InputStream input) throws IOException {
        Element root;
        try {
            root = readAddressBook(input.readAllBytes());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Invalid static address book XML: " + e.getMessage(), e);
        }

        List<Service> services = new ArrayList<>();
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element service)) {
                continue;
            }

            String group = service.getAttribute("group").trim();
            String name = service.getAttribute("name").trim();
            String version = service.getAttribute("version").trim();
            if (StringUtils.isBlank(group) || StringUtils.isBlank(name) || StringUtils.isBlank(version)) {
                throw new IOException("Static address book group, name and version must not be blank");
            }

            String serviceID = "[" + group + "]" + name + "-" + version;
            List<InterAddress> addresses = new ArrayList<>();
            for (Node child = service.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element address)) {
                    continue;
                }
                String value = address.getTextContent().trim();
                if (StringUtils.isBlank(value)) {
                    throw new IOException("Static address must not be blank for " + serviceID);
                }
                try {
                    addresses.add(new InterAddress(value));
                } catch (URISyntaxException | IllegalStateException e) {
                    throw new IllegalArgumentException("Invalid static address for " + serviceID + ": " + value, e);
                }
            }
            services.add(new Service(serviceID, addresses));
        }
        return new AddressBook(services);
    }

    /** 单个服务的身份与全量静态地址，不保存协议筛选策略。 */
    public static final class Service {
        private final String             serviceID;
        private final List<InterAddress> addresses;

        private Service(String serviceID, List<InterAddress> addresses) {
            this.serviceID = serviceID;
            this.addresses = List.copyOf(addresses);
        }

        public String getServiceID() {
            return this.serviceID;
        }

        public List<InterAddress> getAddresses() {
            return this.addresses;
        }
    }

    private static Element readAddressBook(byte[] xml) throws ParserConfigurationException, IOException, SAXException {
        // 首次解析不加载外部 DTD，用于区分两种声明方式；验证成功前不修改地址池。
        Document document = addressBookBuilder(false).parse(new ByteArrayInputStream(xml));
        DocumentType declaration = document.getDoctype();
        if (declaration != null) {
            if (StringUtils.isNotBlank(declaration.getInternalSubset())) {
                throw new SAXException("Static address book does not allow an internal DTD subset");
            }
            document = addressBookBuilder(true).parse(new ByteArrayInputStream(xml));
        } else {
            String resource = "/META-INF/meteor/schema/meteor-address-book.xsd";
            try (InputStream schema = AddressBook.class.getResourceAsStream(resource)) {
                if (schema == null) {
                    throw new FileNotFoundException("Bundled meteor-address-book.xsd not found");
                }
                SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                factory.newSchema(new StreamSource(schema)).newValidator().validate(new DOMSource(document));
            }
        }
        return document.getDocumentElement();
    }

    private static DocumentBuilder addressBookBuilder(boolean validateDtd) throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(validateDtd);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", validateDtd);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            boolean knownPublic = StringUtils.equals(publicId, "-//hasor.net//DTD Meteor Address Book 1.0//EN");
            boolean knownSystem = publicId == null && StringUtils.equals(systemId, "https://www.hasor.net/rsf/schema/meteor-address-book.dtd");
            if (!knownPublic && !knownSystem) {
                throw new SAXException("Unsupported static address book DTD: " + systemId);
            }
            String resource = "/META-INF/meteor/schema/meteor-address-book.dtd";
            try (InputStream dtd = AddressBook.class.getResourceAsStream(resource)) {
                if (dtd == null) {
                    throw new FileNotFoundException("Bundled meteor-address-book.dtd not found");
                }
                InputSource source = new InputSource(new ByteArrayInputStream(dtd.readAllBytes()));
                source.setPublicId(publicId);
                source.setSystemId(systemId);
                return source;
            }
        });

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
        return builder;
    }
}