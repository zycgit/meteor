/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap.python;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import net.hasor.example.contract.types.TypeEchoService;
import net.hasor.example.contract.types.TypeEchoServiceImpl;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.example.contract.types.TypeValueVectors;
import net.hasor.example.protocol.soap.MetXmlJacksonExtension;
import net.hasor.example.protocol.soap.TypeValueXmlMixin;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.matrix.types.TypeValueAssertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** 同一普通 Java DTO 与 Python SDK 原生值双向互通，每个实例只验证一个类型向量。 */
@RunWith(Parameterized.class)
public class SoapPythonTypeMatrixTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    private final String vector;

    public SoapPythonTypeMatrixTest(String type, String vector) {
        this.vector = vector;
    }

    @Parameterized.Parameters(name = "types/python/soap/direct/{0}/{1}")
    public static List<Object[]> vectors() throws Exception {
        // 固定共享向量顺序，使每个类型边界在 JUnit 和矩阵报告中有独立身份。
        List<Object[]> cases = new ArrayList<>();
        for (String vector : TypeValueVectors.names()) {
            cases.add(new Object[] { TypeValueVectors.type(vector), vector });
        }
        return cases;
    }

    @Test(timeout = 60000)
    public void meteorProviderAcceptsPythonNativeType() throws Exception {
        // Meteor 从公开 bootstrap 启动，服务绑定独立 SDK 生成的 WSDL。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext provider = configuration.buildContext(); //
             PythonSoapTypePeer consumer = PythonSoapTypePeer.consumer()) {
            provider.start();
            provider.publisher() //
                    .rsfService(TypeEchoService.class, new TypeEchoServiceImpl()) //
                    .name("types").aliasName("soap", "types").protocol("soap") //
                    .bindResource("soap", new SoapWsdlResource(this.contract())).register();

            // Python 自行从共享输入恢复值并通过 Zeep 实际编码、调用和解码。
            InterAddress listen = provider.getListenAddress("soap");
            URI wsdl = new URI("http", null, listen.getHost(), listen.getPort(), "/soap/types", "wsdl", null);
            TypeValue actual = consumer.echo(wsdl, this.vector);

            // Java 再检查当前字段及服务端收据，不能把 JSON 控制结果当作业务调用本身。
            TypeValue expected = TypeValueVectors.value(this.vector);
            TypeValueAssertions.assertVector(this.vector, expected, actual);
        }
    }

    @Test(timeout = 60000)
    public void meteorConsumerReadsPythonNativeType() throws Exception {
        // soapfish2 在独立容器发布相同契约，Meteor 只配置出站协议。
        MetConfiguration configuration = this.configuration(false);
        try (PythonSoapTypePeer provider = PythonSoapTypePeer.provider(); //
             MetContext consumer = configuration.buildContext()) {
            consumer.start();
            MetBindInfo<TypeEchoService> service = consumer.publisher() //
                    .rsfService(TypeEchoService.class) //
                    .name("types").aliasName("soap", "types").protocol("soap") //
                    .bindResource("soap", new SoapWsdlResource(this.contract())).register();

            // 普通接口代理直接调用 Python 服务，提供者读取原生字段后重新构建响应。
            InterAddress address = provider.address(consumer.getSettings().getUnitName());
            TypeEchoService remote = consumer.getRsfClient(address).getRemote(service);
            TypeValue expected = TypeValueVectors.value(this.vector);
            TypeValue actual = remote.echo(expected);

            // 精确检查所选类型，浮点数保留符号位，整数不经过浮点中转。
            TypeValueAssertions.assertVector(this.vector, expected, actual);
        }
    }

    private MetConfiguration configuration(boolean listen) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.setDataHome(this.data.newFolder("application").toPath());
        var http = configuration.connector("http");
        if (listen) {
            http.bind(ContainerProcess.runnerHost(), 0);
        }
        http.protocol("soap").addressScheme("http").contextPath("/soap");

        // XML 包装是 SOAP 的映射配置，共享 DTO 不依赖特定 SDK 的 Schema 形状。
        XmlMapper mapper = XmlMapper.builder() //
                .enable(ToXmlGenerator.Feature.WRITE_NULLS_AS_XSI_NIL) //
                .enable(ToXmlGenerator.Feature.WRITE_XML_SCHEMA_CONFORMING_FLOATS) //
                .defaultUseWrapper(false).build();
        mapper.addMixIn(TypeValue.class, TypeValueXmlMixin.class);
        configuration.addExtension(context -> new MetXmlJacksonExtension(mapper));
        return configuration;
    }

    private URL contract() throws Exception {
        return Path.of(System.getProperty("python.soap.contract")).resolveSibling("types.wsdl").toUri().toURL();
    }
}
