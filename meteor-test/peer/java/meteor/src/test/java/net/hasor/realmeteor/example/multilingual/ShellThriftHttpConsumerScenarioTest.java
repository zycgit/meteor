/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.thrift.generated.InteropService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.junit.Test;
import static org.junit.Assert.*;

/** curl 原样发送官方 TJSON 消息，Meteor 通过公开入口发布普通 Java Echo 服务。 */
public class ShellThriftHttpConsumerScenarioTest {
    @Test(timeout = 120000)
    public void curlCallsMeteorUsingThriftHttpJson() throws Exception {
        // 官方生成契约只负责消息表示，curl 请求文件由本场景独占并供语言容器共享。
        String value = "Thrift HTTP from curl 中文";
        TMemoryBuffer output = new TMemoryBuffer(128);
        TJSONProtocol writer = new TJSONProtocol(output);
        writer.writeMessageBegin(new TMessage("echo", TMessageType.CALL, 37));
        new InteropService.echo_args(value).write(writer);
        writer.writeMessageEnd();
        Path workspace = OrderPeerProcess.sources().getParent().resolve("build/curl-wire");
        Files.createDirectories(workspace);
        Path directory = Files.createTempDirectory(workspace, "thrift-http-");
        Path request = directory.resolve("request.json");
        Path response = directory.resolve("response.json");
        Path headers = directory.resolve("response.headers");
        try {
            Files.write(request, Arrays.copyOf(output.getArray(), output.length()));

            // 仅创建真实 HTTP/1 监听，通过 bootstrap 启动后发布注解接口，不使用内部编解码器。
            MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
            configuration.connector("http").bind(ContainerProcess.runnerHost(), 0) //
                    .option("httpVersion", 1).protocol("thrift-http") //
                    .contextPath("/thrift").option("encoding", "json");
            try (MetContext server = configuration.buildContext()) {
                server.start();
                server.publisher().rsfService(EchoService.class, text -> text).register();
                int port = server.getListenAddress("thrift-http").getPort();
                String address = "http://" + ContainerProcess.runnerHost() + ":" + port + "/thrift";

                // 独立 node 容器内的 curl 直接连接 Meteor，响应正文与头部保持线上原文。
                List<String> command = List.of("curl", "--http1.1", "--fail-with-body", "--silent", //
                        "--show-error", "--max-time", "15", "--header", "Content-Type: application/x-thrift", //
                        "--dump-header", headers.toString(), "--output", response.toString(), //
                        "--write-out", "%{http_code}\n", "--data-binary", "@" + request, address);
                try (OrderPeerProcess curl = OrderPeerProcess.start("node", command, null)) {
                    assertEquals("200", curl.awaitOutput().trim());
                }

                // 官方 SDK 解读真实响应，方法、消息类型、序号和业务结果共同证明本次往返。
                String fields = Files.readString(headers, StandardCharsets.US_ASCII);
                assertTrue(fields, fields.startsWith("HTTP/1.1 200"));
                boolean thriftContentType = fields.lines() //
                        .anyMatch(line -> StringUtils.equalsIgnoreCase(line, "Content-Type: application/x-thrift"));
                assertTrue(fields, thriftContentType);
                TMemoryInputTransport input = new TMemoryInputTransport(Files.readAllBytes(response));
                TJSONProtocol reader = new TJSONProtocol(input);
                TMessage reply = reader.readMessageBegin();
                assertEquals("echo", reply.name);
                assertEquals(TMessageType.REPLY, reply.type);
                assertEquals(37, reply.seqid);
                InteropService.echo_result result = new InteropService.echo_result();
                result.read(reader);
                reader.readMessageEnd();
                assertTrue("Thrift success field is missing", result.isSetSuccess());
                assertEquals(value, result.getSuccess());
            }
        } finally {
            // 场景只清理自己的临时文件；常驻语言容器仍由外层编排持有。
            Files.deleteIfExists(request);
            Files.deleteIfExists(response);
            Files.deleteIfExists(headers);
            Files.deleteIfExists(directory);
        }
    }

    @MetService(name = "CurlEcho")
    public interface EchoService {
        @MetMethod(name = "echo")
        String echo(@MetParam(name = "value", id = 1) String value);
    }
}
