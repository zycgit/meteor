/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.rsf;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.example.protocol.rsf.RsfEchoService;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfContext;

/** Maven 正式版 Hasor RSF 4.2.5 对端；帧、握手和 Java 序列化均由原 SDK 实现。 */
public final class LegacyRsfPeer {
    private LegacyRsfPeer() {
    }

    public static void main(String[] arguments) throws Exception {
        // 运行类路径只有旧 SDK 及契约，不能意外借用 Meteor 编解码实现。
        if (LegacyRsfPeer.class.getClassLoader().getResource("net/hasor/meteor/MetContext.class") != null) {
            throw new IllegalStateException("Legacy RSF peer must not load Meteor runtime");
        }
        String host = System.getenv().getOrDefault("METEOR_TEST_PEER_HOST", "127.0.0.1");
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }

        // 旧 SDK 的端口 0 表示禁用，因此为本进程选择独占可用端口后再启动。
        Hasor configuration = Hasor.create() //
                .addVariable("RSF_ENABLE", "true") //
                .addVariable("RSF_SERVICE_IP", host) //
                .addVariable("RSF_SERVICE_PORT", Integer.toString(port)) //
                .addVariable("RSF_DISK_CACHE_ENABLE", "false");
        AppContext application = configuration.build();
        try {
            RsfContext context = application.getInstance(RsfContext.class);
            switch (arguments[0]) {
                case "server": {
                    // 提供者只发布当前场景拥有的接口，通过 SDK 生命周期关闭连接和线程。
                    context.publisher().rsfService(RsfEchoService.class, text -> text) //
                            .group("compat").name("RsfEcho").version("1.0.0") //
                            .serialize("Java").register();
                    Runtime.getRuntime().addShutdownHook(new Thread(application::shutdown, "legacy-rsf-shutdown"));
                    System.out.println("READY " + context.bindAddress("RSF/1.0"));
                    System.out.flush();
                    new CountDownLatch(1).await();
                    return;
                }
                case "client": {
                    // 消费者没有本地实现，明确通过网络调用 Meteor 的同名服务。
                    RsfBindInfo<RsfEchoService> service = context.publisher().rsfService(RsfEchoService.class) //
                            .group("compat").name("RsfEcho").version("1.0.0") //
                            .serialize("Java").register();
                    String text = new String(Base64.getDecoder().decode(arguments[2]), StandardCharsets.UTF_8);
                    String result = context.getRsfClient(arguments[1]).getRemote(service).echo(text);
                    String encoded = Base64.getEncoder().encodeToString(result.getBytes(StandardCharsets.UTF_8));
                    System.out.println("RESULT " + encoded);
                    return;
                }
                default: {
                    throw new IllegalArgumentException("Unknown legacy RSF peer role: " + arguments[0]);
                }
            }
        } finally {
            application.shutdown();
        }
    }
}
