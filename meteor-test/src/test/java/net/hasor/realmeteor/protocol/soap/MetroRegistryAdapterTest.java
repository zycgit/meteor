/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPBodyElement;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPMessage;
import net.hasor.cobble.StringUtils;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.realmeteor.environment.discovery.AddressSelectionAwaiter;
import net.hasor.realmeteor.environment.discovery.ForeignServiceRegistrar;
import net.hasor.realmeteor.environment.discovery.RegistryAddressResolver;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.w3c.dom.NodeList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Metro 官方 JVM 复用标准 SOAP 合同，注册发现由显式存储适配器完成。 */
@RunWith(Parameterized.class)
public class MetroRegistryAdapterTest {
    @Parameterized.Parameters(name = "backend={0}, SOAP={1}")
    public static Collection<Object[]> configurations() {
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { "jdbc", "1.1" });
        result.add(new Object[] { "jdbc", "1.2" });
        result.add(new Object[] { "zookeeper", "1.1" });
        result.add(new Object[] { "zookeeper", "1.2" });
        result.add(new Object[] { "nacos", "1.1" });
        result.add(new Object[] { "nacos", "1.2" });
        return result;
    }

    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String backend;
    private final String version;

    public MetroRegistryAdapterTest(String backend, String version) {
        this.backend = backend;
        this.version = version;
    }

    @Test(timeout = 180000)
    public void officialConsumerResolvesMeteorWsdlThroughStorageAdapter() throws Exception {
        // 1.1 复用既有 document/literal WSDL；1.2 复用已有自动生成的 EchoService 合同。
        Path directory = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, directory)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-soap-adapter-provider");
            configuration.connector("http").bind("127.0.0.1", 0).protocol("soap") //
                    .addressScheme("http").contextPath("/services").option("soapVersion", this.version);
            try (MetContext provider = configuration.buildContext()) {
                provider.start();
                var publication = provider.publisher().rsfService(EchoService.class, new EchoServiceImpl()) //
                        .aliasName("soap", "EchoService").protocol("soap");
                if (StringUtils.equals(this.version, "1.1")) {
                    publication.bindResource("soap", this.wsdlResource());
                }
                MetBindInfo<EchoService> service = publication.register();

                // SDK 从注册记录获得 HTTP 挂载，再从该真实服务的线上 WSDL 读取定义。
                try (StorageAccess storage = registry.openStorage(); //
                     RegistryAddressResolver resolver = new RegistryAddressResolver(storage, service.getBindID(), //
                             "soap")) {
                    URI mount = resolver.awaitCount(1).get(0);
                    assertEquals("http", mount.getScheme());
                    assertEquals("/services", mount.getPath());
                    URL wsdl = URI.create(mount + "/EchoService?wsdl").toURL();
                    String namespace = this.namespace();
                    String port = StringUtils.equals(this.version, "1.1") ? "EchoPort" : "EchoServicePort";
                    QName serviceName = new QName(namespace, "EchoService");
                    QName portName = new QName(namespace, port);
                    String action = StringUtils.equals(this.version, "1.1") ? namespace : namespace + "/echo";
                    Map<String, Object> dispatch = SoapPeerProcess.dispatch(wsdl, serviceName, portName, action);

                    // Service/Dispatch 与网络请求属于 Metro 独立 JVM；宿主仅传递控制数据和 XML。
                    String expected = "注册发现 SOAP 中文 🙂 <&>";
                    Map<String, Object> result = SoapPeerProcess.invokeMetro(dispatch, this.message(expected));
                    SOAPMessage response = SoapPeerProcess.response(result);
                    String types = StringUtils.equals(this.version, "1.1") ? "urn:meteor:test:echo:types" : "";
                    String name = StringUtils.equals(this.version, "1.1") ? "result" : "return";
                    NodeList values = response.getSOAPBody().getElementsByTagNameNS(types, name);
                    assertEquals(1, values.getLength());
                    assertEquals(expected, values.item(0).getTextContent());
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void meteorConsumerDiscoversOfficialProviderThroughStorageAdapter() throws Exception {
        // 仅配置出站 SOAP，业务调用的目标完全来自注册库自动订阅。
        Path directory = this.data.newFolder("registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open(this.backend, directory)) {
            RegistryConfiguration registry = environment.configuration();
            MetConfiguration configuration = this.configuration(registry, "meteor-soap-adapter-consumer");
            configuration.connector("http").protocol("soap") //
                    .addressScheme("http").contextPath("/services").option("soapVersion", this.version);
            try (MetContext consumer = configuration.buildContext()) {
                consumer.start();
                var subscription = consumer.publisher().rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService").protocol("soap");
                if (StringUtils.equals(this.version, "1.1")) {
                    subscription.bindResource("soap", this.wsdlResource());
                }
                MetBindInfo<EchoService> service = subscription.register();
                assertTrue(consumer.getUpdater().queryAvailableAddresses(service.getBindID()).isEmpty());

                // 原互通 Metro Provider 的正式发布合同不变，适配器仅登记它真实监听的 PID 和 URL。
                try (SoapPeerProcess provider = SoapPeerProcess.metroServer("echo", this.version, false); //
                     ForeignServiceRegistrar registrar = new ForeignServiceRegistrar(registry)) {
                    String unit = consumer.getSettings().getUnitName();
                    InterAddress address = new InterAddress("http", "127.0.0.1", provider.getPort(), unit);
                    registrar.register(service, provider.processId(), "java", "soap", "http", address, //
                            "/services/EchoService");
                    RegistryDiscoveryExample.awaitDiscovery(consumer, service);
                    AddressSelectionAwaiter.await(consumer, service, "echo");

                    // Meteor 代理通过自动选址直接连接 Metro，不由适配器转发 SOAP 报文。
                    EchoService remote = consumer.getRsfClient().getRemote(service);
                    String expected = "官方服务发现 SOAP 中文 🙂 <&>";
                    assertEquals(expected, remote.echo(expected));
                }
            }
        }
    }

    private MetConfiguration configuration(RegistryConfiguration registry, String application) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.setApplicationName(application).addExtension(registry);
        configuration.connector("tcp").protocol("rsf");
        return configuration;
    }

    private SoapWsdlResource wsdlResource() throws Exception {
        URL wsdl = this.getClass().getResource("/soap/wsdl/echo.wsdl");
        assertNotNull("Missing standard SOAP interoperability WSDL", wsdl);
        return new SoapWsdlResource(wsdl);
    }

    private String namespace() {
        return StringUtils.equals(this.version, "1.1") ? "urn:meteor:test:echo" : "urn:hasor:meteor:soap:EchoService";
    }

    private SOAPMessage message(String value) throws Exception {
        boolean soap11 = StringUtils.equals(this.version, "1.1");
        String protocol = soap11 ? SOAPConstants.SOAP_1_1_PROTOCOL : SOAPConstants.SOAP_1_2_PROTOCOL;
        SOAPMessage message = MessageFactory.newInstance(protocol).createMessage();
        String namespace = soap11 ? "urn:meteor:test:echo:types" : this.namespace();
        SOAPBodyElement wrapper = message.getSOAPBody().addBodyElement(new QName(namespace, "echo", "service"));
        QName parameter = soap11 ? new QName(namespace, "message", "types") : new QName("arg0");
        wrapper.addChildElement(parameter).addTextNode(value);
        message.saveChanges();
        return message;
    }
}
