/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.address;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.hasor.example.echo.TextEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** XML 保留全部静态地址，消费端可用协议由本地连接器决定。 */
@NxnRpc(name = "rsf", version = "1")
@NxnRpc(name = "jsonrpc", version = "2.0")
@ParameterizedClass(autoCloseArguments = false, name = "{0}", quoteTextArguments = false)
@ValueSource(strings = { "address-book/consumer-xsd.xml", "address-book/consumer-dtd.xml" })
public class AddressBookConsumerScenarioTest {
    @TempDir
    public Path directory;
    private final String resource;

    public AddressBookConsumerScenarioTest(String resource) {
        this.resource = resource;
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void staticProviderListSupportsBothRsfAndJsonRpc() throws Exception {
        // 提供者用普通发布代码开放两个协议，XML 只由消费端加载。
        MetConfiguration provider = new MetConfiguration().setUnitName("local");
        provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        provider.connector("http").bind("127.0.0.1", 0).protocol("jsonrpc").contextPath("/rpc");
        try (MetContext server = provider.buildContext()) {
            Map<String, String> received = new ConcurrentHashMap<>();
            server.publisher().bindFilter("inbound-scheme", (request, response, chain) -> {
                assertFalse(request.isLocal());
                received.put((String) request.getParameterObject()[0], request.getRemoteAddress().getSchema());
                chain.doFilter(request, response);
            });
            MetBindInfo<TextEchoService> published = server.publisher() // 同一个服务只发布一次。
                    .rsfService(TextEchoService.class, value -> "echo:" + value) //
                    .group("consumer-address-book").name("echo").version("1.0").register();
            server.start();
            String book = writeAddressBook(server);
            MetConfiguration consumer = new MetConfiguration().setUnitName("local").setAddressBook(book);
            consumer.connector("tcp").protocol("rsf");
            consumer.connector("http").protocol("jsonrpc").contextPath("/rpc");

            try (MetContext client = consumer.buildContext()) {
                MetBindInfo<TextEchoService> subscribed = client.publisher().rsfService(TextEchoService.class) //
                        .group("consumer-address-book").name("echo").version("1.0").register();
                client.start();
                String id = subscribed.getBindID();
                assertEquals(Set.of("rsf", "jsonrpc"), client.getUpdater().getSupportedProtocols(id));
                List<InterAddress> available = client.getUpdater().queryAvailableAddresses(id);
                assertEquals(2, available.size());

                // 每一个消费端候选地址都要真正完成网络调用，验证两个协议同时可用。
                for (InterAddress address : available) {
                    TextEchoService remote = client.getRsfClient(address).getRemote(subscribed);
                    assertEquals("echo:" + address.getSchema(), remote.echo(address.getSchema()));
                }
                assertEquals(Map.of("rsf", "rsf", "jsonrpc", "jsonrpc"), received);
                assertTrue(published.getBindProtocols().isEmpty());
                assertEquals(Set.of("rsf", "jsonrpc"), MetBindInfoWrap.unwrap(published).getProtocolMetas().keySet());
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void consumerConnectorsSelectFromTheSameUnfilteredAddressBook() throws Exception {
        // 两个消费端加载同一份全量地址本，只启用各自的 JSON-RPC 或 RSF 连接器。
        MetConfiguration provider = new MetConfiguration().setUnitName("local");
        provider.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        provider.connector("http").bind("127.0.0.1", 0).protocol("jsonrpc").contextPath("/rpc");
        try (MetContext server = provider.buildContext()) {
            Map<String, String> received = new ConcurrentHashMap<>();
            server.publisher().bindFilter("inbound-scheme", (request, response, chain) -> {
                assertFalse(request.isLocal());
                received.put((String) request.getParameterObject()[0], request.getRemoteAddress().getSchema());
                chain.doFilter(request, response);
            });
            MetBindInfo<TextEchoService> published = server.publisher() // 提供者的代码声明没有协议限制。
                    .rsfService(TextEchoService.class, value -> "echo:" + value) //
                    .group("consumer-address-book").name("echo").version("1.0").register();
            server.start();
            String book = writeAddressBook(server);
            MetConfiguration consumer = new MetConfiguration().setUnitName("local").setAddressBook(book);
            consumer.connector("http").protocol("jsonrpc").contextPath("/rpc");
            MetConfiguration otherConsumer = new MetConfiguration().setUnitName("local").setAddressBook(book);
            otherConsumer.connector("tcp").protocol("rsf");

            // 两个服务订阅采用相同身份，启动时分别加载同一个地址本文件。
            try (MetContext client = consumer.buildContext(); //
                 MetContext other = otherConsumer.buildContext()) {
                MetBindInfo<TextEchoService> subscribed = client.publisher().rsfService(TextEchoService.class) //
                        .group("consumer-address-book").name("echo").version("1.0").register();
                MetBindInfo<TextEchoService> otherService = other.publisher().rsfService(TextEchoService.class) //
                        .group("consumer-address-book").name("echo").version("1.0").register();
                client.start();
                other.start();
                String id = subscribed.getBindID();
                String otherID = otherService.getBindID();

                // 全量地址完全一致，可用列表只受各自连接器支持范围影响。
                assertEquals(Set.of("jsonrpc"), client.getUpdater().getSupportedProtocols(id));
                assertEquals(List.of(server.getListenAddress("jsonrpc")),
                        client.getUpdater().queryAvailableAddresses(id));
                assertEquals(2, client.getUpdater().queryAllAddresses(id).size());
                assertEquals(Set.of("rsf"), other.getUpdater().getSupportedProtocols(otherID));
                assertEquals(List.of(server.getListenAddress("rsf")),
                        other.getUpdater().queryAvailableAddresses(otherID));
                assertEquals(2, other.getUpdater().queryAllAddresses(otherID).size());

                // 分别经真实网络调用，提供者的两个协议仍同时可用。
                TextEchoService selected = client.getRsfClient().getRemote(subscribed);
                assertEquals("echo:restricted", selected.echo("restricted"));
                TextEchoService rsf = other.getRsfClient().getRemote(otherService);
                assertEquals("echo:other", rsf.echo("other"));
                assertEquals(Map.of("restricted", "jsonrpc", "other", "rsf"), received);
                assertTrue(published.getBindProtocols().isEmpty());
                assertEquals(Set.of("rsf", "jsonrpc"), MetBindInfoWrap.unwrap(published).getProtocolMetas().keySet());
            }
        }
    }

    private String writeAddressBook(MetContext server) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/" + this.resource)) {
            assertNotNull(input);
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8) // 仅生成本场景的实际端口文件。
                    .replace("${rsfAddress}", server.getListenAddress("rsf").toString()) //
                    .replace("${jsonrpcAddress}", server.getListenAddress("jsonrpc").toString());
            Path book = this.directory.resolve("address-book.xml");
            Files.writeString(book, xml);
            return book.toUri().toString();
        }
    }
}
