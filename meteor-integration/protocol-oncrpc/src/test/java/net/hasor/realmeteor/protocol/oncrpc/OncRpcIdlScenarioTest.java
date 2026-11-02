/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import java.net.DatagramSocket;
import java.net.InetAddress;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.oncrpc.IdlPoint;
import net.hasor.example.protocol.oncrpc.IdlService;
import net.hasor.example.protocol.oncrpc.IdlServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcIdlResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** 从 bootstrap 发布普通接口和 DTO，两个传输分别验证文件绑定。 */
public class OncRpcIdlScenarioTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void tcpCallMapsAnOrdinaryDtoUsingTheFile() throws Exception {
        // 先启动独立的服务容器，再分别发布和订阅服务。
        try (MetContext server = this.configuration("tcp", "oncrpc", true).buildContext(); //
             MetContext client = this.configuration("tcp", "oncrpc", false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(IdlService.class, new IdlServiceImpl()) //
                    .bindResource("oncrpc", this.resource()).register();
            MetBindInfo<IdlService> service = client.publisher().rsfService(IdlService.class) //
                    .bindResource("oncrpc", this.resource()).register();

            // 方法与 DTO 名称保持 Java 风格，程序身份和成员顺序由文件提供。
            IdlService remote = client.getRsfClient(server.getListenAddress("oncrpc")).getRemote(service);
            IdlPoint point = new IdlPoint();
            point.setX(7);
            point.setY(9);
            IdlPoint result = remote.mirror(point);

            assertNotSame(point, result);
            assertEquals(7, result.getX());
            assertEquals(9, result.getY());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void udpCallRestoresTheDifferentJavaParameterOrder() throws Exception {
        // 同一契约通过 UDP 发布，复用已有超时、重传和回包管理。
        try (MetContext server = this.configuration("udp", "oncrpc-udp", true).buildContext(); //
             MetContext client = this.configuration("udp", "oncrpc-udp", false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(IdlService.class, new IdlServiceImpl()) //
                    .bindResource("oncrpc-udp", this.resource()).register();
            MetBindInfo<IdlService> service = client.publisher().rsfService(IdlService.class) //
                    .bindResource("oncrpc-udp", this.resource()).register();

            // Java 参数 right、left 与文件 left、right 顺序相反，不能交换实际含义。
            IdlService remote = client.getRsfClient(server.getListenAddress("oncrpc-udp")).getRemote(service);
            assertEquals(15, remote.difference(4, 19));
        }
    }

    private MetConfiguration configuration(String transport, String protocol, boolean listen) throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(protocol);
        MetConfiguration.ConnectorSettings connector = configuration.connector(transport);
        if (listen) {
            int port = 0;
            if (StringUtils.equals(transport, "udp")) {
                try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                    port = socket.getLocalPort();
                }
            }
            connector.bind("127.0.0.1", port);
        }
        connector.protocol(protocol);
        return configuration;
    }

    private OncRpcIdlResource resource() throws Exception {
        return new OncRpcIdlResource(this.getClass().getResource("/xdr/points.x"));
    }
}
