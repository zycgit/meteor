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
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import org.junit.Test;
import static org.junit.Assert.*;

public class HessianProtocolTest {
    @Test
    public void spiDiscoversTheHessianHttpContract() {
        // 从正式插件资源发现提供者，验证实际发布的 SPI 契约。
        ProtocolProvider<?> provider = ServiceLoader.load(ProtocolProvider.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(value -> StringUtils.equals("hessian", value.name())) //
                .findFirst() //
                .orElse(null);

        // 协议入口、传输和默认编码共同确定插件的正式身份。
        assertNotNull(provider);
        assertEquals(HessianProtocol.class, provider.getClass());
        assertEquals("hessian", provider.name());
        assertEquals("http", provider.transportType());
        assertEquals("Hessian", provider.defaultSerialize());
        assertEquals(HttpExchange.class, provider.messageType());
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
        // 通过协议提供者校验普通 Java 服务，发布方无需提供额外资源。
        ServiceDomain<HessianDemoService> service = new ServiceDomain<>(HessianDemoService.class);
        service.setBindName("echo");
        service.putAliasName("hessian", "echo");
        ProtocolConfig config = this.protocol();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ProtocolBinding binding = new HessianProtocol().validate(config, null, meta);

        // 无需定义文件也会生成当前挂载自己的接入结果。
        assertNotNull(binding);
        assertNotNull(binding.getDefinition());
    }

    @Test
    public void providerRejectsStreamingServiceBeforePublication() {
        // 发布校验必须经过提供者委托，不能等收到网络请求才发现不支持 Flow。
        ServiceDomain<StreamingService> service = new ServiceDomain<>(StreamingService.class);
        service.setBindName("stream");
        service.putAliasName("hessian", "stream");
        HessianProtocol provider = new HessianProtocol();
        ProtocolConfig config = this.protocol();

        // Hessian 不接受该挂载，其他已配置协议仍可独立判断服务签名。
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(provider.validate(config, null, meta));
    }

    private ProtocolConfig protocol() {
        return ProtocolConfig.newConfig("hessian", "hessian", Map.of("contextPath", "/hessian"));
    }

    public interface StreamingService {
        Flow.Publisher<String> values();
    }
}
