/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import java.net.URL;
import java.util.Map;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.meteor.protocol.soap.scenario.example.MultiPartService;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WebServicesMultiPartTest {
    @Test(timeout = 20000)
    public void multipleBodyOutputPartsBecomeOneJavaMap() throws Exception {
        // 导入两个独立输出元素的外部 WSDL，Java 使用一个 Map 结果承接各 part。
        URL wsdl = this.getClass().getResource("/wsdl/multipart-output.wsdl");
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("http").bind("127.0.0.1", 0).protocol("soap").contextPath("/services");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("http").protocol("soap").contextPath("/services");
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.start();
            client.start();
            MultiPartService implementation = name -> Map.of("greeting", "Hello " + name, "identity", name);
            server.publisher().rsfService(MultiPartService.class, implementation) //
                    .aliasName("soap", "Parts").protocol("soap") //
                    .bindResource(new SoapWsdlResource(wsdl)).register();
            MetBindInfo<MultiPartService> service = client.publisher().rsfService(MultiPartService.class) //
                    .aliasName("soap", "Parts").protocol("soap") //
                    .bindResource(new SoapWsdlResource(wsdl)).register();

            // 经真实 HTTP 同时验证服务端拆分与客户端重组，名称取自 WSDL part。
            MultiPartService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(service);
            Map<String, String> result = remote.describe("Meteor");
            assertEquals(Map.of("greeting", "Hello Meteor", "identity", "Meteor"), result);
        }
    }
}
