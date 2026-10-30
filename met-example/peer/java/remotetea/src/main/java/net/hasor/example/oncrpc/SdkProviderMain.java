/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.oncrpc;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.example.OrderQuery;
import org.acplt.oncrpc.OncRpcException;
import org.acplt.oncrpc.XdrInt;
import org.acplt.oncrpc.XdrVoid;
import org.acplt.oncrpc.server.OncRpcCallInformation;
import org.acplt.oncrpc.server.OncRpcDispatchable;
import org.acplt.oncrpc.server.OncRpcServerTransportRegistrationInfo;
import org.acplt.oncrpc.server.OncRpcTcpServerTransport;

/** RemoteTea 独立 ONC RPC 提供者，由 Meteor 消费者调用。 */
public final class SdkProviderMain implements OncRpcDispatchable {
    static final int PROGRAM = 0x31234569;
    static final int VERSION = 1;

    private final OrderDemoServiceImpl orders = new OrderDemoServiceImpl();

    private SdkProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // 直接在本地发布服务，无需 rpcbind。
        OncRpcServerTransportRegistrationInfo registration =
                new OncRpcServerTransportRegistrationInfo(PROGRAM, VERSION);
        OncRpcTcpServerTransport server = new OncRpcTcpServerTransport(new SdkProviderMain(),
                InetAddress.getByName("127.0.0.1"), 8080,
                new OncRpcServerTransportRegistrationInfo[] { registration }, 8192);
        try {
            server.setCharacterEncoding(StandardCharsets.US_ASCII.name());
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "oncrpc-sdk-provider-shutdown"));
            server.listen();
            System.out.println("SDK order provider is ready: tcp://127.0.0.1:8080");
            OrderExampleUtils.awaitShutdown();
        } finally {
            server.close();
        }
    }

    @Override
    public void dispatchOncRpcCall(OncRpcCallInformation call, int program, int version, int procedure)
            throws OncRpcException, IOException {
        // 程序及版本匹配后，按 order.x 中的过程编号分派。
        if (program != PROGRAM) {
            call.failProgramUnavailable();
            return;
        }
        if (version != VERSION) {
            call.failProgramMismatch(VERSION, VERSION);
            return;
        }

        switch (procedure) {
            case 0:
                call.retrieveCall(XdrVoid.XDR_VOID);
                call.reply(XdrVoid.XDR_VOID);
                break;
            case 1:
                SdkOrderValue input = new SdkOrderValue(null);
                call.retrieveCall(input);
                Order saved = this.orders.save(input.getOrder());
                call.reply(new SdkOrderValue(saved));
                break;
            case 2:
                XdrInt id = new XdrInt();
                call.retrieveCall(id);
                OrderQuery query = new OrderQuery();
                query.setId(id.intValue());
                Order found = this.orders.find(query);
                call.reply(new SdkOrderValue(found));
                break;
            default:
                call.failProcedureUnavailable();
        }
    }
}
