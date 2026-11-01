/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.example.multilingual;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedClass;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.HessianInputFactory;
import com.google.protobuf.ByteString;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import net.hasor.cobble.StringUtils;
import net.hasor.example.Order;
import net.hasor.example.OrderContract;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** curl 负责真实 HTTP 发送；固定 unary 的二进制正文由既有官方契约与 SDK 读写。 */
@ParameterizedClass(autoCloseArguments = false, name = "curl/{0}/direct", quoteTextArguments = false)
@MethodSource("protocols")
@NxnRpc(name = "grpc", cases = "curl/grpc/direct")
@NxnRpc(name = "grpc-web", cases = "curl/grpc-web/direct")
@NxnRpc(name = "hprose", cases = "curl/hprose/direct")
@NxnRpc(name = "hessian", version = "2", cases = "curl/hessian/direct")
@NxnRpc(name = "triple", cases = "curl/triple/direct")
public class ShellBinaryConsumerScenarioTest {
    private final String protocol;

    public ShellBinaryConsumerScenarioTest(String protocol) {
        this.protocol = protocol;
    }
    public static List<Object[]> protocols() {
        // 顺序对应 HTTP/2、gRPC-Web 和传统 HTTP 二进制的基础消费矩阵。
        List<Object[]> protocols = new ArrayList<>();
        protocols.add(new Object[] { "grpc" });
        protocols.add(new Object[] { "grpc-web" });
        protocols.add(new Object[] { "hprose" });
        protocols.add(new Object[] { "hessian" });
        protocols.add(new Object[] { "triple" });
        return protocols;
    }

    @Test
    @Timeout(value = 120000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void curlReceivesTheSavedOrderFromMeteor() throws Throwable {
        // 各容器共享测试工作区；正文文件不放在容器私有的 /tmp，也不共用其它场景目录。
        Path workspace = OrderPeerProcess.sources().getParent().resolve("build/curl-wire");
        Files.createDirectories(workspace);
        Path directory = Files.createTempDirectory(workspace, this.protocol + "-");
        Path request = directory.resolve("request.bin");
        Path response = directory.resolve("response.bin");
        Path headers = directory.resolve("response.headers");
        Path trace = directory.resolve("curl.trace");
        try {
            Files.write(request, this.requestBody());

            // 独立 Meteor 应用经 bootstrap 发布当前协议，curl 直接访问该应用，不经转发代理。
            try (OrderPeerProcess provider = OrderPeerProcess.javaMain(this.protocol, "Provider", null)) {
                String target = provider.awaitReady("Order provider is ready: ");
                List<String> command = this.command(target, request, response, headers);
                try (OrderPeerProcess curl = OrderPeerProcess.start("node", command, null)) {
                    assertEquals("200", curl.awaitOutput().trim());
                }

                // 除 HTTP 成功外还检查真实线上正文和 gRPC 最终状态，错误不能仅凭 HTTP 200 通过。
                String fields = Files.readString(headers, StandardCharsets.US_ASCII);
                System.out.println("[curl " + this.protocol + "] response headers:\n" + fields.replace("\r", "\\r"));
                String version = "HTTP/1.1 200";
                if (this.http2()) {
                    version = "HTTP/2 200";
                }
                assertTrue(fields.startsWith(version), fields);
                this.assertSavedOrder(Files.readAllBytes(response), fields);
            }
        } finally {
            // 保留真实 curl 收包证据，避免临时文件清理后只剩下“状态缺失”的断言。
            if (Files.exists(trace)) {
                System.err.println("[curl " + this.protocol + "] wire trace:\n" + Files.readString(trace));
            }
            // 只释放本次场景自己创建的文件，进程和独占日志由各自所有者关闭。
            Files.deleteIfExists(request);
            Files.deleteIfExists(response);
            Files.deleteIfExists(headers);
            Files.deleteIfExists(trace);
            Files.deleteIfExists(directory);
        }
    }

    private List<String> command(String target, Path request, Path response, Path headers) {
        List<String> command = new ArrayList<>(List.of("curl", "--fail-with-body", "--silent", "--show-error"));
        command.addAll(List.of("--max-time", "15", "--dump-header", headers.toString()));
        command.addAll(List.of("--output", response.toString(), "--write-out", "%{http_code}\n"));
        String version = "--http1.1";
        if (this.http2()) {
            version = "--http2-prior-knowledge";
            // curl 7.88 达到 Content-Length 后会提前结束；等待真正的 HTTP/2 EOF 才能交付 trailers。
            command.add("--ignore-content-length");
            Path trace = headers.resolveSibling("curl.trace");
            command.addAll(List.of("--trace-ascii", trace.toString(), "--trace-time"));
        }
        command.add(version);

        // 传输版本和线上媒体类型显式固定，二进制请求通过文件原样交给 curl。
        String address = target + "/" + this.protocol;
        String contentType;
        if (StringUtils.equals("hprose", this.protocol)) {
            contentType = "application/hprose";
        } else if (StringUtils.equals("hessian", this.protocol)) {
            address += "/orders";
            contentType = "application/x-hessian";
        } else {
            if (StringUtils.equals("triple", this.protocol)) {
                address = target;
            }
            address += "/meteor.example.OrderDemoService/save";
            contentType = "application/grpc+proto";
            if (StringUtils.equals("grpc-web", this.protocol)) {
                contentType = "application/grpc-web+proto";
                command.addAll(List.of("--header", "x-grpc-web: 1"));
            } else {
                command.addAll(List.of("--header", "te: trailers"));
            }
        }
        command.addAll(List.of("--header", "content-type: " + contentType));
        command.addAll(List.of("--data-binary", "@" + request, address));
        return command;
    }

    private boolean http2() {
        return StringUtils.equals("grpc", this.protocol) || StringUtils.equals("triple", this.protocol);
    }

    private byte[] requestBody() throws Exception {
        Order order = new Order();
        order.setId(1001);
        order.setDescription("Order from curl binary");
        order.setAttachment("Meteor".getBytes(StandardCharsets.UTF_8));

        // SDK writer 负责对象表示；此处只选择固定方法和单个参数，不调用 Meteor 的编码器。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (StringUtils.equals("hprose", this.protocol)) {
            bytes.write('C');
            HproseWriter writer = new HproseWriter(bytes);
            writer.writeString("orders_save");
            writer.reset();
            writer.writeArray(new Object[] { order });
            bytes.write('z');
            return bytes.toByteArray();
        }
        if (StringUtils.equals("hessian", this.protocol)) {
            Hessian2Output writer = new Hessian2Output(bytes);
            writer.call("save", new Object[] { order });
            writer.close();
            return bytes.toByteArray();
        }

        // 官方生成的 Protobuf 消息外仅加标准的五字节、无压缩 unary 长度头。
        byte[] protobuf = this.protobufOrder().toByteArray();
        return ByteBuffer.allocate(5 + protobuf.length).put((byte) 0).putInt(protobuf.length).put(protobuf).array();
    }

    private OrderContract.Order protobufOrder() {
        return OrderContract.Order.newBuilder() //
                .setId(1001).setDescription("Order from curl binary") //
                .setAttachment(ByteString.copyFromUtf8("Meteor")).build();
    }

    private void assertSavedOrder(byte[] body, String headers) throws Throwable {
        if (StringUtils.equals("hprose", this.protocol)) {
            assertEquals(List.of("application/hprose"), this.headerValues(headers, "content-type"));
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            assertEquals('R', input.read());
            Order saved = new HproseReader(input).unserialize(Order.class);
            this.assertSavedFields(saved);
            assertEquals('z', input.read());
            assertEquals(-1, input.read());
            return;
        }
        if (StringUtils.equals("hessian", this.protocol)) {
            assertEquals(List.of("application/x-hessian"), this.headerValues(headers, "content-type"));
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            HessianInputFactory.HeaderType header = new HessianInputFactory().readHeader(input);
            assertEquals(HessianInputFactory.HeaderType.HESSIAN_2, header);
            Hessian2Input reader = new Hessian2Input(input);
            try {
                this.assertSavedFields((Order) reader.readReply(Order.class));
            } finally {
                reader.close();
            }
            return;
        }

        // 固定 unary 必须恰有一条数据帧；gRPC-Web 的最终状态位于独立的正文 trailers 帧。
        ByteBuffer frames = ByteBuffer.wrap(body);
        byte[] protobuf = this.frame(frames, 0);
        assertEquals(this.protobufOrder(), OrderContract.Order.parseFrom(protobuf));
        if (StringUtils.equals("grpc-web", this.protocol)) {
            String trailers = new String(this.frame(frames, 0x80), StandardCharsets.US_ASCII);
            assertEquals(List.of("application/grpc-web+proto"), this.headerValues(headers, "content-type"));
            assertEquals(List.of("0"), this.headerValues(trailers, "grpc-status"));
        } else {
            assertEquals(List.of("application/grpc+proto"), this.headerValues(headers, "content-type"));
            assertEquals(//
                    List.of("0"), this.headerValues(headers, "grpc-status"), "gRPC status is missing from curl headers:\n" + headers);
        }
        assertEquals(0, frames.remaining(), "Unexpected extra unary response bytes");
    }

    private byte[] frame(ByteBuffer input, int expectedFlag) {
        assertTrue(input.remaining() >= 5, "Truncated unary frame header");
        assertEquals(expectedFlag, input.get() & 0xff);
        int length = input.getInt();
        assertTrue(length >= 0 && length <= input.remaining(), "Invalid unary frame length");
        byte[] payload = new byte[length];
        input.get(payload);
        return payload;
    }

    private List<String> headerValues(String fields, String name) {
        List<String> result = new ArrayList<>();
        for (String field : fields.split("\\R")) {
            int colon = field.indexOf(':');
            if (colon > 0 && StringUtils.equalsIgnoreCase(name, field.substring(0, colon).trim())) {
                result.add(field.substring(colon + 1).trim());
            }
        }
        return result;
    }

    private void assertSavedFields(Order saved) {
        assertNotNull(saved);
        assertEquals(1001, saved.getId());
        assertEquals("Order from curl binary", saved.getDescription());
        assertArrayEquals("Meteor".getBytes(StandardCharsets.UTF_8), saved.getAttachment());
    }
}
