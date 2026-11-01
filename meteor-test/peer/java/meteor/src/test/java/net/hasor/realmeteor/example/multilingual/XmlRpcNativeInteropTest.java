/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.util.ArrayList;
import java.util.List;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** xmlrpc-c 的 C 与现代 C++ API 各自验证一次基础双向文本互通。 */
@ParameterizedClass(autoCloseArguments = false, name = "sdk/{0}/xmlrpc/xmlrpc-c@{1}/{2}", quoteTextArguments = false)
@MethodSource("languages")
@NxnRpc(name = "xmlrpc")
public class XmlRpcNativeInteropTest {
    private final String language;
    private final String executable;

    public XmlRpcNativeInteropTest(String language, String version, String profile, String executable) {
        this.language = language;
        this.executable = executable;
    }
    public static List<Object[]> languages() {
        // 发行包与上游 Super Stable 分别编译 C/C++，不能共用系统旧库。
        List<Object[]> versions = new ArrayList<>();
        String baseline = "/opt/meteor-peer/native/xmlrpc/";
        versions.add(new Object[] { "c", "1.33.14", "legacy-debian", baseline + "c/echo-peer" });
        versions.add(new Object[] { "cpp", "1.33.14", "legacy-debian", baseline + "cpp/echo-peer" });
        String latest = "/opt/meteor-peer/native/versions/xmlrpc/1.64.04/";
        versions.add(new Object[] { "c", "1.64.04", "upstream-latest", latest + "c/echo-peer" });
        versions.add(new Object[] { "cpp", "1.64.04", "upstream-latest", latest + "cpp/echo-peer" });
        return versions;
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sdkToMeteorXmlRpcEcho() throws Exception {
        // 正式启动 Meteor 后发布文本接口，C/C++ 对端自行完成 HTTP 和 XML-RPC 编解码。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                .protocol("xmlrpc").contextPath("/rpc");
        try (MetContext provider = configuration.buildContext()) {
            provider.start();
            provider.publisher().rsfService(TextEchoService.class, text -> text) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc").register();

            // 原生 SDK 验证 Unicode、XML 转义及返回值，正常退出不能代替结果校验。
            String message = this.language + " → Meteor 中文 <&>";
            int port = provider.getListenAddress("xmlrpc").getPort();
            String target = "http://" + ContainerProcess.runnerHost() + ":" + port + "/rpc";
            List<String> command = List.of(this.executable, "consumer", message);
            try (OrderPeerProcess consumer = OrderPeerProcess.start("native", command, target)) {
                String output = consumer.awaitOutput();
                assertTrue(output.contains("OK xmlrpc-c "), output);
                assertTrue(output.contains(message), output);
            }
        }
    }

    @Test
    @Timeout(value = 60000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void meteorToSdkXmlRpcEcho() throws Exception {
        // 原生 SDK 提供者使用独占动态端口，Meteor 只创建出站 HTTP 通道。
        MetConfiguration configuration = new MetConfiguration().setRequestTimeout(10000);
        configuration.connector("http").protocol("xmlrpc").contextPath("/rpc");
        List<String> command = List.of(this.executable, "provider");
        try (OrderPeerProcess provider = OrderPeerProcess.start("native", command, null); //
             MetContext consumer = configuration.buildContext()) {
            String target = provider.awaitReady("READY ");
            consumer.start();
            MetBindInfo<TextEchoService> service = consumer.publisher().rsfService(TextEchoService.class) //
                    .aliasName("xmlrpc", "demo").protocol("xmlrpc").register();

            // 没有本地实现的公开客户端必须经过网络并取得原生服务的回声。
            TextEchoService remote = consumer.getRsfClient(new InterAddress(target)).getRemote(service);
            String message = "Meteor → " + this.language + " 中文 <&>";
            assertEquals(message, remote.echo(message));
        }
    }
}
