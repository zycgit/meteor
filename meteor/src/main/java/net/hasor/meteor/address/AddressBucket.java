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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import net.hasor.cobble.ResourcesUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.dsl.FlowRuleParser;
import net.hasor.meteor.address.route.unit.UnitFlowControl;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 描述：用于接收地址更新同时也用来计算有效和无效地址。
 * 也负责提供服务地址列表集，负责分类存储和处理同一个服务的各种类型的服务地址数据，比如：
 * <ol>
 *  <li>同单元服务地址</li>
 *  <li>有效服务地址</li>
 *  <li>不可用服务地址</li>
 *  <li>全部服务地址</li>
 * </ol>
 * 所有对服务地址的进一 步处理都需要使用{@link #getAvailableAddresses()}获得的地址列表。
 * 如果应用了本地机房策略，则本地
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2014年9月12日
 */
public class AddressBucket extends Observable {
    public static final    String                                        LOGGER_NAME   = "rsf-address";
    protected static final Logger                                        addressLogger = LoggerFactory.getLogger(LOGGER_NAME);
    protected static final Logger                                        logger        = LoggerFactory.getLogger(AddressBucket.class);
    //流控&路由
    private volatile       FlowControlRef                                flowControlRef;     //默认流控规则引用
    private volatile       RuleRef                                       ruleRef;
    //原始数据
    private final          String                                        serviceID;          //服务ID
    private final          String                                        unitName;           //服务所属单元
    private final          List<InterAddress>                            allAddressList;     //所有备选地址
    private final          List<InterAddress>                            staticAddressList;  //不会失效的地址（即使是注册中心推送也不会失效）
    //运行时动态更新的地址
    private final          ConcurrentMap<InterAddress, InnerInvalidInfo> invalidAddresses;   //失效状态统计信息
    //下面时计算出来的数据
    private                List<InterAddress>                            localUnitAddresses; //本单元地址
    private                List<InterAddress>                            availableAddresses; //所有可用地址（包括本地单元）

    public AddressBucket(String serviceID, String unitName) {
        this.flowControlRef = FlowControlRef.defaultRef();
        this.ruleRef = new RuleRef(null);
        this.serviceID = serviceID;
        this.unitName = unitName;
        this.allAddressList = new CopyOnWriteArrayList<>();
        this.staticAddressList = new CopyOnWriteArrayList<>();
        this.invalidAddresses = new ConcurrentHashMap<>();
        this.localUnitAddresses = new ArrayList<>();
        this.availableAddresses = new ArrayList<>();
        this.refreshAddress();
    }

    public String getServiceID() {
        return this.serviceID;
    }

    public FlowControlRef getFlowControlRef() {
        return this.flowControlRef;
    }

    public RuleRef getRuleRef() {
        return this.ruleRef;
    }

    /** 合入已准备好的服务初始配置，保留提前发现的地址及未覆盖的规则。 */
    void initialize(AddressBucket prepared) {
        synchronized (this) {
            for (InterAddress address : prepared.staticAddressList) {
                if (!this.allAddressList.contains(address)) {
                    this.allAddressList.add(address);
                }
                if (!this.staticAddressList.contains(address)) {
                    this.staticAddressList.add(address);
                }
                this.invalidAddresses.remove(address);
            }
            if (prepared.flowControlRef.flowControlScript != null) {
                this.flowControlRef = prepared.flowControlRef;
            }

            RuleRef merged = new RuleRef(this.ruleRef);
            RuleRef initial = prepared.ruleRef;
            if (initial.getServiceLevel().isEnable()) {
                merged.setServiceLevel(initial.getServiceLevel());
            }
            if (initial.getMethodLevel().isEnable()) {
                merged.setMethodLevel(initial.getMethodLevel());
            }
            if (initial.getArgsLevel().isEnable()) {
                merged.setArgsLevel(initial.getArgsLevel());
            }
            this.ruleRef = merged;
            this.refreshAvailableAddress();
        }
        this.notifyObservers(this);
    }

    /** 获取所有地址（包括本地的和无效的） */
    public synchronized List<InterAddress> getAllAddresses() {
        return new ArrayList<>(this.allAddressList);
    }

    /** 获取静态地址 */
    public synchronized List<InterAddress> getStaticAddresses() {
        return new ArrayList<>(this.staticAddressList);
    }

    /** 获取计算之后可用的地址 */
    public synchronized List<InterAddress> getAvailableAddresses() {
        return new ArrayList<>(this.availableAddresses);
    }

    /** 失效地址 */
    public synchronized List<InterAddress> getInvalidAddresses() {
        return new ArrayList<>(this.invalidAddresses.keySet());
    }

    /** 获取计算之后同一单元地址 */
    public synchronized List<InterAddress> getLocalUnitAddresses() {
        return new ArrayList<>(this.localUnitAddresses);
    }

    /** 新增地址支持动态新增 */
    public void newAddress(Collection<InterAddress> newHostSet, AddressTypeEnum type) {
        synchronized (this) {
            Objects.requireNonNull(newHostSet, "addresses");
            Objects.requireNonNull(type, "address type");
            for (InterAddress address : newHostSet) {
                if (address == null) {
                    continue;
                }
                if (!this.allAddressList.contains(address)) {
                    this.allAddressList.add(address);
                }
                if (type == AddressTypeEnum.Static && !this.staticAddressList.contains(address)) {
                    this.staticAddressList.add(address);
                }
                this.invalidAddresses.remove(address);
            }
            refreshAvailableAddress();
        }
        notifyObservers(this);
    }

    /**
     * 将地址置为失效的(对于静态地址,该方法无效)。
     * @param address 失效的地址。
     * @param timeoutMs 失效时长
     */
    public void invalidAddress(InterAddress address, long timeoutMs) {
        synchronized (this) {
            if (this.staticAddressList.contains(address) || !this.allAddressList.contains(address)) {
                return;
            }

            InnerInvalidInfo info = this.invalidAddresses.get(address);
            if (info == null) {
                this.invalidAddresses.put(address, new InnerInvalidInfo(timeoutMs));
            } else {
                info.invalid(timeoutMs);
            }

            refreshAvailableAddress();
        }
        notifyObservers(this);
    }

    /**
     * 将地址从地址本中删除。
     * @param address 要被删除的地址。
     */
    public void removeAddress(InterAddress address) {
        synchronized (this) {
            if (!this.allAddressList.contains(address)) {
                addressLogger.warn("removeAddress(" + this.serviceID + ") -> targetAddress =" + address + " ,addr is not exist.");
                return;
            } else {
                addressLogger.info("removeAddress(" + this.serviceID + ") -> targetAddress =" + address);
            }

            this.allAddressList.remove(address);
            this.staticAddressList.remove(address);
            this.invalidAddresses.remove(address);
            refreshAvailableAddress();
        }
        notifyObservers(this);
    }

    /** 刷新地址计算结果 */
    public void refreshAddress() {
        synchronized (this) {
            refreshAvailableAddress();
        }
        notifyObservers(this);
    }

    /** Replace registry-provided addresses, preserving explicitly configured static addresses. */
    public void refreshAddressToNew(List<InterAddress> addressList) {
        synchronized (this) {
            Objects.requireNonNull(addressList, "addresses");
            LinkedHashSet<InterAddress> replacement = new LinkedHashSet<>(this.staticAddressList);
            for (InterAddress address : addressList) {
                if (address != null) {
                    replacement.add(address);
                }
            }

            this.allAddressList.clear();
            this.allAddressList.addAll(replacement);
            this.invalidAddresses.clear();
            refreshAvailableAddress();
        }
        notifyObservers(this);
    }

    /** 刷新地址 */
    private void refreshAvailableAddress() {
        //1.计算出有效的地址。
        List<InterAddress> availableList = new ArrayList<>();
        for (InterAddress addressInfo : this.allAddressList) {
            boolean doAdd = true;
            for (InterAddress invalid : this.invalidAddresses.keySet()) {
                if (addressInfo.equals(invalid)) {
                    doAdd = false;
                    break;
                }
            }

            //当失效的地址达到重试时间之后，再次刷新地址时候不被列入失效名单。
            InnerInvalidInfo info = this.invalidAddresses.get(addressInfo);
            if (info != null && info.reTry()) {
                doAdd = true;
            }
            if (doAdd) {
                availableList.add(addressInfo);//有效的
            }
        }

        //2.机房单元化过滤
        List<InterAddress> unitList = availableList;
        if (this.flowControlRef != null && this.flowControlRef.unitFlowControl != null) {
            UnitFlowControl unitFlowControl = this.flowControlRef.unitFlowControl;
            unitList = unitFlowControl.siftUnitAddress(this.unitName, availableList);
            if (unitList == null || unitList.isEmpty()) {
                unitList = availableList;
            }
            if (!unitFlowControl.isLocalUnit(availableList.size(), unitList.size())) {
                unitList = availableList;
            }
        }

        {
            StringBuilder strBuffer1 = new StringBuilder("[");
            for (InterAddress addr : availableList) {
                strBuffer1.append(addr.toHostSchema());
                strBuffer1.append(",");
            }
            strBuffer1.append("]");
            addressLogger.info("refreshAvailableAddress(" + this.serviceID + ") -> availableList =" + strBuffer1);
            StringBuilder strBuffer2 = new StringBuilder("[");
            for (InterAddress addr : unitList) {
                strBuffer2.append(addr.toHostSchema());
                strBuffer2.append(",");
            }
            strBuffer2.append("]");
            addressLogger.info("refreshAvailableAddress(" + this.serviceID + ") -> unitList =" + strBuffer2);
        }

        this.availableAddresses = availableList;
        this.localUnitAddresses = unitList;
        this.setChanged();
    }

    /** 更新服务的流控规则 */
    public boolean updateFlowControl(String script) {
        boolean empty = StringUtils.isBlank(script);
        String normalized = empty ? null : script;
        if (StringUtils.equals(normalized, this.flowControlRef.flowControlScript)) {
            return false;
        }

        // XML 解析只使用输入数据，不占用地址本读写锁。
        FlowControlRef prepared = FlowControlRef.defaultRef();
        if (!empty) {
            try {
                prepared = new FlowRuleParser().parse(script);
            } catch (IllegalArgumentException failure) {
                logger.error("Cannot parse XML flow control", failure);
                return false;
            }
        }
        synchronized (this) {
            if (StringUtils.equals(normalized, this.flowControlRef.flowControlScript)) {
                return false;
            }
            this.flowControlRef = prepared;
            this.refreshAvailableAddress();
        }

        this.notifyObservers(this);
        return true;
    }

    public boolean updateRoute(RouteTypeEnum routeType, String script) {
        RuleRef prepared = new RuleRef(this.ruleRef);
        if (!RouteTypeEnum.updateScript(routeType, script, prepared)) {
            return false;
        }

        synchronized (this) {
            // 基于最新引用只替换当前层级，避免并发更新覆盖其他层级的规则。
            RuleRef updated = new RuleRef(this.ruleRef);
            switch (routeType) {
                case ServiceLevel -> updated.setServiceLevel(prepared.getServiceLevel());
                case MethodLevel -> updated.setMethodLevel(prepared.getMethodLevel());
                case ArgsLevel -> updated.setArgsLevel(prepared.getArgsLevel());
            }
            this.ruleRef = updated;
            this.setChanged();
        }

        this.notifyObservers(this);
        return true;
    }

    @Override
    public String toString() {
        return "AddressBucket - " + this.getServiceID() + //
                " ,unit = " + this.unitName + //
                " ,allAddress size = " + this.allAddressList.size();
    }

    /** 服务须已注册；位置为空时不加载。使用内置 XSD/DTD 离线校验，资源定位遵循 Cobble。 */
    public static void loadAddressBook(MetContext context, String location) throws IOException {
        if (StringUtils.isBlank(location)) {
            return;
        }

        Element root;
        try (InputStream input = ResourcesUtils.getResourceAsStream(context.getClassLoader(), location)) {
            if (input == null) {
                throw new FileNotFoundException("Static address book not found: " + location);
            }
            root = readAddressBook(input.readAllBytes());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Invalid static address book XML: " + location + ": " + e.getMessage(), e);
        }

        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element serviceNode)) {
                continue;
            }
            String group = serviceNode.getAttribute("group").trim();
            String name = serviceNode.getAttribute("name").trim();
            String version = serviceNode.getAttribute("version").trim();
            if (StringUtils.isBlank(group) || StringUtils.isBlank(name) || StringUtils.isBlank(version)) {
                throw new IOException("Static address book group, name and version must not be blank");
            }
            MetBindInfo<?> service = context.getServiceInfo(group, name, version);
            if (service == null) {
                String identity = "[" + group + "]" + name + "-" + version;
                throw new IllegalArgumentException("Unregistered static address book service: " + identity);
            }

            List<InterAddress> addresses = new ArrayList<>();
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element addressNode)) {
                    continue;
                }
                String value = addressNode.getTextContent().trim();
                if (StringUtils.isBlank(value)) {
                    throw new IOException("Static address must not be blank for " + service.getBindID());
                }
                try {
                    addresses.add(new InterAddress(value));
                } catch (URISyntaxException | IllegalStateException e) {
                    throw new IllegalArgumentException("Invalid static address for " + service.getBindID() + ": " + value, e);
                }
            }

            context.getUpdater().appendStaticAddress(service.getBindID(), addresses);
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
            try (InputStream schema = AddressBucket.class.getResourceAsStream("/META-INF/meteor/schema/meteor-address-book.xsd")) {
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
            try (InputStream dtd = AddressBucket.class.getResourceAsStream("/META-INF/meteor/schema/meteor-address-book.dtd")) {
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
