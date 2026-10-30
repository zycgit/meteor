/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import net.hasor.example.protocol.dubbo.WireEcho;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 插件从公开 bootstrap 完成一次基本网络调用。 */
public class DubboBootstrapTest {
    @Test(timeout = 15000)
    public void zeroWireTimeoutUsesTheFiniteServerDefault() throws Exception {
        // 通过正式入口启动服务，在真实入站过滤器中观察执行层最终采用的期限。
        try (MetContext server = this.configuration(true).buildContext()) {
            AtomicInteger timeout = new AtomicInteger();
            server.publisher().bindFilter("observe-timeout", (request, response, chain) -> {
                timeout.set(request.getTimeout());
                chain.doFilter(request, response);
            });
            server.start();
            server.publisher().rsfService(WireEcho.class, value -> value) //
                    .aliasName("dubbo", WireEcho.class.getName()).register();

            // 发送标准 Dubbo 请求，仅把线上 timeout 显式写为旧 SDK 产生的 0。
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Hessian2Output output = new Hessian2Output(bytes);
            output.writeString("2.0.2");
            output.writeString(WireEcho.class.getName());
            output.writeString("1.0.0");
            output.writeString("echo");
            output.writeString("Ljava/lang/String;");
            output.writeString("zero-timeout");
            Map<String, String> attachments = new LinkedHashMap<>();
            attachments.put("group", "default");
            attachments.put("version", "1.0.0");
            attachments.put("timeout", "0");
            output.writeObject(attachments);
            output.flush();
            byte[] body = bytes.toByteArray();
            byte[] frame = ByteBuffer.allocate(16 + body.length) //
                    .putShort((short) 0xdabb).put((byte) 0xc2).put((byte) 0) //
                    .putLong(123).putInt(body.length).put(body).array();

            // 真实调用成功，MetRequest 得到 5000ms 默认值，既非无限等待也非伪造的 1ms。
            InterAddress address = server.getListenAddress("dubbo");
            try (Socket socket = new Socket(address.getHost(), address.getPort())) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(frame);
                DataInputStream incoming = new DataInputStream(socket.getInputStream());
                byte[] header = incoming.readNBytes(16);
                int length = ByteBuffer.wrap(header).getInt(12);
                byte[] response = incoming.readNBytes(length);
                Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(response));
                assertEquals(20, header[3]);
                assertEquals(4, input.readInt());
                assertEquals("zero-timeout", input.readString());
                assertEquals(5000, timeout.get());
            }
        }
    }

    @Test(timeout = 15000)
    public void unicodeValueSurvivesTheDefaultHessian2RoundTrip() throws Exception {
        // 只在服务端开放监听，两个容器均通过公开 bootstrap 创建。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            // 启动后发布和订阅相同 Java 接口，未指定编码时采用 Dubbo 的 Hessian2 默认值。
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .aliasName("dubbo", DubboInteropService.class.getName()).register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));
            DubboInteropService remote = caller.getRemote(service);

            // Unicode 内容经网络编码和解码后保持原值。
            assertEquals("你好 Dubbo 🌏", remote.echo("你好 Dubbo 🌏"));
        }
    }

    @Test(timeout = 15000)
    public void rejectedDubboEncodingKeepsTheLocalServiceCallable() throws Exception {
        // 容器只有 Dubbo 出站能力，服务明确选择不被该协议接受的编码。
        try (MetContext context = this.configuration(false).buildContext()) {
            context.start();
            MetBindInfo<DubboInteropService> service = context.publisher() //
                    .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .serialize("json").register();

            // 协议拒绝只影响网络发布，本地服务对象及调用入口继续有效。
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertEquals(service.getBindID(), registered.getBindID());
            DubboInteropService local = context.getRsfClient().getRemote(service);
            assertEquals("local-only", local.echo("local-only"));
        }
    }

    @Test(timeout = 15000)
    public void rejectedDubboEncodingKeepsTheRsfPublicationCallable() throws Exception {
        // 同一 TCP 端点挂载 RSF 和 Dubbo，两端仅以 JSON 调用普通服务。
        MetConfiguration serverConfig = new MetConfiguration().setDefaultProtocol("rsf");
        var serverTcp = serverConfig.connector("tcp").bind("127.0.0.1", 0);
        serverTcp.protocol("rsf");
        serverTcp.protocol("dubbo");
        MetConfiguration clientConfig = new MetConfiguration().setDefaultProtocol("rsf");
        clientConfig.connector("tcp").protocol("rsf");
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.start();
            client.start();
            MetBindInfo<DubboInteropService> published = server.publisher() //
                    .rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .serialize("json").register();
            MetBindInfo<DubboInteropService> subscribed = client.publisher() //
                    .rsfService(DubboInteropService.class).serialize("json").register();

            // Dubbo 不适配 JSON 的结果不影响同一服务通过 RSF 收发请求。
            MetBindInfo<?> registered = server.getServiceInfo(published.getBindID());
            assertEquals(published.getBindID(), registered.getBindID());
            DubboInteropService remote = client.getRsfClient(server.getListenAddress("rsf")) //
                    .getRemote(subscribed);
            assertEquals("rsf-only", remote.echo("rsf-only"));
        }
    }

    @Test(timeout = 15000)
    public void rejectedDubboEncodingDoesNotExposeTheServiceOverTheNetwork() throws Exception {
        // 提供者明确选择 JSON；消费者使用 Dubbo 支持的 Hessian2 发起请求。
        try (MetContext server = this.configuration(true).buildContext(); //
             MetContext client = this.configuration(false).buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .serialize("json").register();
            MetBindInfo<DubboInteropService> service = client.publisher() //
                    .rsfService(DubboInteropService.class).serialize("Hessian2").register();
            MetClient caller = client.getRsfClient(server.getListenAddress("dubbo"));

            // 已启动的协议监听不能通过服务别名访问被拒绝的提供者。
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "not-published" };
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> caller.syncInvoke(service, "echo", parameterTypes, arguments));
            assertTrue(failure.getCause().getMessage().contains("Unknown Dubbo service"));
        }
    }

    private MetConfiguration configuration(boolean listener) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo");
        configuration.setDefaultTimeout(5000);
        var tcp = configuration.connector("tcp");
        if (listener) {
            tcp.bind("127.0.0.1", 0);
        }
        tcp.protocol("dubbo");
        return configuration;
    }
}
