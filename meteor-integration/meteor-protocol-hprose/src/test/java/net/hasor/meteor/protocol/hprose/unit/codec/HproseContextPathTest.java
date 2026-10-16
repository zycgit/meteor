/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.unit.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.hprose.codec.HproseHttpCodecTestAccess.DecodedCall;
import net.hasor.meteor.protocol.hprose.codec.HproseHttpCodecTestAccess.HttpCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseContextPathTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void defaultDiscoveryOmitsServicesFromExtraMounts() throws Exception {
        // 同一协议声明默认与附加挂载点，两个服务分别只使用一个挂载点。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher() //
                    .rsfService(Echo.class, value -> value) //
                    .name("default") //
                    .aliasName("Hprose", "default") //
                    .register();
            context.publisher() //
                    .rsfService(Echo.class, value -> value) //
                    .name("private") //
                    .aliasName("Hprose", "private") //
                    .contextPathByPrefix("hprose", "/rpc/private") //
                    .register();

            // 默认入口的发现请求只返回默认挂载点上的方法。
            HttpCodec codec = new HttpCodec(this.protocol(context), context);
            HttpRequest request = HttpRequest.newBuffered("GET", "/rpc", Map.of(), new byte[0], 1, Map.of());
            byte[] body = codec.decodeRequest(request).immediate().body();
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            assertEquals('F', input.read());
            String[] functions = new HproseReader(input).unserialize(String[].class);
            assertArrayEquals(new String[] { "default_echo" }, functions);
            assertEquals('z', input.read());
            assertEquals(-1, input.read());
        }
    }

    @Test
    public void discoveryUsesTheLongestMountWhenTheUriHasAQuery() throws Exception {
        // 附加挂载点嵌套在默认挂载点之下，服务只在附加点可见。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher() //
                    .rsfService(Echo.class, value -> value) //
                    .name("default") //
                    .aliasName("Hprose", "default") //
                    .register();
            context.publisher() //
                    .rsfService(Echo.class, value -> value) //
                    .name("private") //
                    .aliasName("Hprose", "private") //
                    .contextPathByPrefix("hprose", "/rpc/private") //
                    .register();
            HttpCodec codec = new HttpCodec(this.protocol(context), context);

            // GET 的查询字符串不改变挂载点选择，也不能泄露父挂载点的函数。
            String uri = "/rpc/private/functions?context=/rpc";
            HttpRequest request = HttpRequest.newBuffered("GET", uri, Map.of(), new byte[0], 1, Map.of());
            byte[] body = codec.decodeRequest(request).immediate().body();
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            assertEquals('F', input.read());
            assertArrayEquals(new String[] { "private_echo" }, new HproseReader(input).unserialize(String[].class));
            assertEquals('z', input.read());
            assertEquals(-1, input.read());
        }
    }

    @Test
    public void methodsOutsideTheMountDoNotCauseAliasAmbiguity() throws Exception {
        // 两个服务共用函数别名，但分别只发布到默认和附加挂载点。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher() //
                    .rsfService(Echo.class, value -> value) //
                    .name("default") //
                    .aliasName("Hprose", "shared") //
                    .register();
            context.publisher() //
                    .rsfService(Echo.class, value -> value) //
                    .name("private") //
                    .aliasName("Hprose", "shared") //
                    .contextPathByPrefix("hprose", "/rpc/private") //
                    .register();
            HttpCodec codec = new HttpCodec(this.protocol(context), context);

            // 只允许当前挂载点上的服务参与重载选择。
            byte[] body = this.call("shared_echo");
            HttpRequest request = HttpRequest.newBuffered("POST", "/rpc/private", Map.of(), body, 1, Map.of());
            DecodedCall call = codec.decodeRequest(request).calls().get(0);
            assertNull(call.failure());
            assertEquals("private", call.request().getServiceName());
        }
    }

    @Test
    public void aBatchAppliesOneMountToEveryDecodedCall() throws Exception {
        // 默认服务与附加服务共同出现在一个批次中，访问范围由本次 HTTP 请求决定。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher() //
                    .rsfService(Echo.class, value -> value) //
                    .name("default") //
                    .aliasName("Hprose", "default") //
                    .register();
            context.publisher() //
                    .rsfService(Echo.class, value -> value) //
                    .name("private") //
                    .aliasName("Hprose", "private") //
                    .contextPathByPrefix("hprose", "/rpc/private") //
                    .register();
            HttpCodec codec = new HttpCodec(this.protocol(context), context);

            // 将两个完整调用组合成单个批次，并发送到附加挂载点。
            ByteArrayOutputStream batch = new ByteArrayOutputStream();
            byte[] denied = this.call("default_echo");
            batch.write(denied, 0, denied.length - 1);
            batch.write(this.call("private_echo"));
            byte[] body = batch.toByteArray();
            HttpRequest request = HttpRequest.newBuffered("POST", "/rpc/private", Map.of(), body, 1, Map.of());
            List<DecodedCall> calls = codec.decodeRequest(request).calls();

            // 被隔离服务在原批次位置失败，可见服务仍正常解码。
            assertEquals(2, calls.size());
            assertNull(calls.get(0).request());
            MetException failure = (MetException) calls.get(0).failure();
            assertEquals(ProtocolStatus.Forbidden, failure.getStatus());
            assertNull(calls.get(1).failure());
            assertEquals("private", calls.get(1).request().getServiceName());
        }
    }

    @Test
    public void outboundRequestsPreferTheDefaultWhenExplicitlyIncluded() throws Exception {
        // 默认挂载点列在服务显式配置的第二项，出站仍优先选择它。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher() //
                    .rsfService(Echo.class) //
                    .contextPathByPrefix("hprose", "/rpc/private", "/rpc") //
                    .register();
            HttpCodec codec = new HttpCodec(this.protocol(context), context);

            // 出站编码直接使用订阅服务的路径选择，不读取先前入站请求。
            HttpRequest request = codec.encodeRequest(this.request(service));
            assertEquals("/rpc", request.uri());
        }
    }

    @Test
    public void outboundRequestsUseTheFirstExplicitMountWhenDefaultIsExcluded() throws Exception {
        // 两个显式挂载点都排除默认入口，服务声明顺序决定出站入口。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher() //
                    .rsfService(Echo.class) //
                    .contextPathByPrefix("hprose", "/alternate", "/rpc/private") //
                    .register();
            HttpCodec codec = new HttpCodec(this.protocol(context), context);

            // 协议配置中附加入口的声明顺序不能覆盖服务自己的选择。
            HttpRequest request = codec.encodeRequest(this.request(service));
            assertEquals("/alternate", request.uri());
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http") //
                .protocol("hprose") //
                .contextPath("/rpc") //
                .addContextPath("/rpc/private") //
                .addContextPath("/alternate");
        return configuration;
    }

    private ProtocolConfig protocol(MetContext context) {
        ConnectorConfig connector = context.getSettings().getConnectorConfigs().iterator().next();
        return connector.protocol("hprose");
    }

    private RequestPayload request(MetBindInfo<?> service) {
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod("echo");
        request.addParameter(String.class.getName(), "value");
        return request;
    }

    private byte[] call(String function) throws Exception {
        // 方法名和参数各自使用独立引用表，生成一条完整调用帧。
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write('C');
        HproseWriter writer = new HproseWriter(body);
        writer.writeString(function);
        writer.reset();
        writer.writeArray(new Object[] { "value" });
        body.write('z');
        return body.toByteArray();
    }
}
