/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.Flow;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.hessian.HessianDemoService;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolFactory;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.domain.ServiceDomain;
import org.junit.Test;
import static org.junit.Assert.*;

public class HessianProtocolTest {
    @Test
    public void spiDiscoversTheHessianHttpContract() {
        // 从正式插件资源发现工厂，验证实际发布的 SPI 契约。
        ProtocolFactory<?> factory = ServiceLoader.load(ProtocolFactory.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(value -> StringUtils.equals("hessian", value.name())) //
                .findFirst() //
                .orElse(null);

        // 协议入口、传输和默认编码共同确定插件的正式身份。
        assertNotNull(factory);
        assertEquals(HessianProtocol.class, factory.getClass());
        assertEquals("hessian", factory.name());
        assertEquals("http", factory.transportType());
        assertEquals("Hessian", factory.defaultSerializeType());
        assertEquals(HttpExchange.class, factory.messageType());
    }

    @Test
    public void protocolSelectionUsesMountedRoutesInsteadOfBodyProbing() {
        // HTTP 挂载已确定协议，不根据正文猜测 Hessian 服务入口。
        ProtocolConfig config = this.protocol();
        HttpRequest request = HttpRequest.newBuffered("POST", "/hessian/echo", Map.of(), //
                new byte[0], 1, Map.of());
        HttpExchange exchange = new HttpExchange(request);

        // 未选择具体挂载时，协议探测不能抢占其它 HTTP 协议。
        assertEquals(RouteProbeResult.REJECT, new HessianProtocol().probe(config, exchange));
    }

    @Test
    public void ordinaryServiceCanBePublishedWithoutBindingAnIdl() {
        // 通过协议工厂校验普通 Java 服务，发布方无需提供额外资源。
        ServiceDomain<HessianDemoService> service = new ServiceDomain<>(HessianDemoService.class);
        service.setBindName("echo");
        service.putAliasName("hessian", "echo");
        new HessianProtocol().validateService(this.protocol(), service);
    }

    @Test
    public void factoryRejectsStreamingServiceBeforePublication() {
        // 发布校验必须经过工厂委托，不能等收到网络请求才发现不支持 Flow。
        ServiceDomain<StreamingService> service = new ServiceDomain<>(StreamingService.class);
        service.setBindName("stream");
        service.putAliasName("hessian", "stream");
        HessianProtocol factory = new HessianProtocol();
        ProtocolConfig config = this.protocol();

        // Hessian RPC 的普通响应不能承载持续输出的 Publisher。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> factory.validateService(config, service));
        assertTrue(StringUtils.startsWith(failure.getMessage(), "Hessian does not support streaming type:"));
    }

    private ProtocolConfig protocol() {
        return ProtocolConfig.newConfig("hessian", "hessian", Map.of("contextPath", "/hessian"));
    }

    public interface StreamingService {
        Flow.Publisher<String> values();
    }
}
