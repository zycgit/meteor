/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import net.hasor.meteor.test.nxn.NxnRpc;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** TCP record marking 的多个 fragment 经真实网络进入两端协议会话。 */
@NxnRpc(name = "oncrpc", version = "2")
public class OncRpcTcpRecordScenarioTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void tcpReassemblesFragmentedRequestAndReplyRecords() throws Exception {
        // 两端选择普通 ONC RPC TCP，监听地址由公开启动 API 分配。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("oncrpc");
        incoming.setDefaultTimeout(10000);
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("oncrpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("oncrpc");
        outgoing.setDefaultTimeout(10000);
        outgoing.connector("tcp").protocol("oncrpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器，再发布和订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 透明中继不执行 RPC，仅把两个方向的 record 正文拆成带有独立 marker 的 fragment。
            InterAddress target = server.getListenAddress("oncrpc");
            try (OncRpcRecordRelay relay = new OncRpcRecordRelay(target)) {
                InterAddress address = new InterAddress("oncrpc", "127.0.0.1", relay.getPort(), target.getFormUnit());
                OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);
                byte[] request = new byte[48000];
                for (int index = 0; index < request.length; index++) {
                    request[index] = (byte) (index * 31);
                }

                // 大报文跨越多个 record fragment 后保持完整，两个方向均已实际发送多个 fragment。
                assertArrayEquals(request, remote.bytes(request));
                relay.assertHealthy();
                assertTrue(relay.getRequestFragments() > 1, "request must contain multiple record fragments");
                assertTrue(relay.getResponseFragments() > 1, "reply must contain multiple record fragments");
            }
        }
    }
}
