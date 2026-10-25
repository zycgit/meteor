/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.nio.file.Path;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.thrift.annotated.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftIdlResource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** 普通 Java DTO 与官方生成契约双向互通，每个参数组合都是独立场景。 */
@RunWith(Parameterized.class)
public class ThriftAnnotatedDtoScenarioTest {
    private final String  encoding;
    private final String  transport;
    private final boolean multiplexed;

    public ThriftAnnotatedDtoScenarioTest(String encoding, String transport, boolean multiplexed) {
        this.encoding = encoding;
        this.transport = transport;
        this.multiplexed = multiplexed;
    }

    @Parameterized.Parameters(name = "{0}/{1}/multiplexed={2}")
    public static Collection<Object[]> matrix() {
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[] { "binary", "tcp", false });
        cases.add(new Object[] { "compact", "tcp", false });
        cases.add(new Object[] { "json", "tcp", false });
        cases.add(new Object[] { "binary", "raw", false });
        cases.add(new Object[] { "compact", "raw", false });
        cases.add(new Object[] { "json", "raw", false });
        cases.add(new Object[] { "binary", "http", false });
        cases.add(new Object[] { "compact", "http", false });
        cases.add(new Object[] { "json", "http", false });
        cases.add(new Object[] { "binary", "tcp", true });
        cases.add(new Object[] { "compact", "tcp", true });
        cases.add(new Object[] { "json", "tcp", true });
        cases.add(new Object[] { "binary", "tls", false });
        cases.add(new Object[] { "compact", "tls", false });
        cases.add(new Object[] { "json", "tls", false });
        cases.add(new Object[] { "binary", "https", false });
        cases.add(new Object[] { "compact", "https", false });
        cases.add(new Object[] { "json", "https", false });
        return cases;
    }

    @Test(timeout = 30000)
    public void providerAcceptsTheOfficialGeneratedDto() throws Exception {
        // Meteor 只发布普通接口和 DTO，编号和异常绑定现有 IDL 文件，不依赖生成资源。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(AnnotatedInteropService.class, new AnnotatedInteropServiceImpl()) //
                    .aliasName(this.protocol(), "ExtendedInteropService") //
                    .bindResource(this.protocol(), this.contract()).register();

            // 独立 JVM 的官方 Client 校验生成 Sample 的全部字段往返一致。
            int port = server.getListenAddress(this.protocol()).getPort();
            List<String> arguments = this.peerArguments("client", port);
            arguments.add("--scenario=dto");
            try (ThriftPeerProcess peer = ThriftPeerProcess.start(arguments.toArray(String[]::new))) {
                peer.requireResult("dto-ok");
            }
        }
    }

    @Test(timeout = 30000)
    public void consumerReadsTheOfficialGeneratedDtoAsAnOrdinaryDto() throws Exception {
        // 官方 Processor 运行在隔离 JVM；Meteor 使用同一普通 Java 接口订阅。
        List<String> arguments = this.peerArguments("server", 0);
        MetConfiguration configuration = this.configuration(false);
        try (ThriftPeerProcess peer = ThriftPeerProcess.start(arguments.toArray(String[]::new)); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();
            client.start();
            MetBindInfo<AnnotatedInteropService> service = client.publisher() //
                    .rsfService(AnnotatedInteropService.class) //
                    .aliasName(this.protocol(), "ExtendedInteropService") //
                    .bindResource(this.protocol(), this.contract()).register();
            InterAddress address = new InterAddress(this.scheme(), peer.host(), port, "default");
            AnnotatedInteropService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 返回值为普通 DTO，包含非 ordinal 枚举和非生成 union。
            AnnotatedSample expected = this.sample();
            AnnotatedSample result = remote.mirror(expected);
            this.assertSample(expected, result);
        }
    }

    private ThriftIdlResource contract() throws Exception {
        Path source = Path.of("src/test/resources/thrift/interop.thrift");
        return new ThriftIdlResource(source.toUri().toURL()).selectService("ExtendedInteropService");
    }

    private MetConfiguration configuration(boolean listen) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(this.protocol());
        configuration.setDefaultTimeout(10000);
        String type = this.http() ? "http" : "tcp";
        ConnectorSettings connector = configuration.connector(type);
        if (listen) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        if (this.secure()) {
            if (listen) {
                ThriftTlsUtils.serverTls(connector, "server", "REQUIRE", "TLSv1.3,TLSv1.2");
            } else {
                ThriftTlsUtils.clientTls(connector, "client", "trust", "TLSv1.3,TLSv1.2");
            }
            if (this.http()) {
                ThriftTlsUtils.https(connector, 1);
            }
        }
        MetConfiguration.ProtocolSettings protocol = connector.protocol(this.protocol()) //
                .addressScheme(this.scheme()).option("encoding", this.encoding) //
                .option("multiplexed", this.multiplexed);
        if (this.http()) {
            connector.option("httpVersion", 1);
            protocol.contextPath("/thrift");
        } else {
            protocol.option("framed", !StringUtils.equals(this.transport, "raw"));
        }
        return configuration;
    }

    private List<String> peerArguments(String command, int port) throws Exception {
        String nativeTransport = StringUtils.equals(this.transport, "raw") ? "tcp" : this.transport;
        List<String> arguments = new ArrayList<>();
        arguments.add("--command=" + command);
        arguments.add("--transport=" + nativeTransport);
        arguments.add("--protocol=" + this.encoding);
        arguments.add("--framed=" + !StringUtils.equals(this.transport, "raw"));
        arguments.add("--multiplexed=" + this.multiplexed);
        arguments.add("--service-name=ExtendedInteropService");
        arguments.add("--port=" + port);
        arguments.add("--path=/thrift");
        if (this.secure()) {
            String identity = StringUtils.equals(command, "server") ? "server" : "client";
            arguments.add("--key-store=" + this.store(identity));
            arguments.add("--trust-store=" + this.store("trust"));
            arguments.add("--client-auth=true");
            arguments.add("--password=meteor-test");
        }
        return arguments;
    }

    private String store(String identity) throws Exception {
        String resource = "/thrift/tls/acceptance/" + identity + ".p12";
        return Path.of(getClass().getResource(resource).toURI()).toString();
    }

    private boolean http() {
        return StringUtils.equals(this.transport, "http") || StringUtils.equals(this.transport, "https");
    }

    private boolean secure() {
        return StringUtils.equals(this.transport, "tls") || StringUtils.equals(this.transport, "https");
    }

    private String protocol() {
        return this.http() ? "thrift-http" : "thrift";
    }

    private String scheme() {
        if (this.secure()) {
            return this.http() ? "https" : "thrifts";
        }
        return this.protocol();
    }

    private AnnotatedSample sample() {
        AnnotatedSample value = new AnnotatedSample();
        value.setId(5_000_000_007L);
        value.setText("Ada 汉字");
        value.setEnabled(true);
        value.setSmall((short) -1234);
        value.setCount(-123_456);
        value.setRatio(42.5);
        value.setPayload(new byte[] { 0, 1, 127, -128, -1 });
        value.setTags(Arrays.asList("alpha", "汉字"));
        value.setLabels(Map.of("language", "中文"));
        value.setCodes(new LinkedHashSet<>(Arrays.asList(7, -9)));
        value.setTiny((byte) -17);
        value.setNote("present");
        value.setOptionalCount(0);
        value.setDefaultCount(29);
        value.setShade(AnnotatedShade.RED);
        AnnotatedChoice choice = new AnnotatedChoice();
        choice.setText("selected");
        value.setChoice(choice);
        value.setRequestId(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"));
        return value;
    }

    private void assertSample(AnnotatedSample expected, AnnotatedSample result) {
        assertEquals(expected.getId(), result.getId());
        assertEquals(expected.getText(), result.getText());
        assertEquals(expected.getEnabled(), result.getEnabled());
        assertEquals(expected.getSmall(), result.getSmall());
        assertEquals(expected.getCount(), result.getCount());
        assertEquals(expected.getRatio(), result.getRatio(), 0);
        assertArrayEquals(expected.getPayload(), result.getPayload());
        assertEquals(expected.getTags(), result.getTags());
        assertEquals(expected.getLabels(), result.getLabels());
        assertEquals(expected.getCodes(), result.getCodes());
        assertEquals(expected.getTiny(), result.getTiny());
        assertEquals(expected.getNote(), result.getNote());
        assertEquals(expected.getOptionalCount(), result.getOptionalCount());
        assertEquals(expected.getDefaultCount(), result.getDefaultCount());
        assertEquals(expected.getShade(), result.getShade());
        assertEquals(expected.getChoice().getText(), result.getChoice().getText());
        assertNull(result.getChoice().getCount());
        assertNull(result.getChoice().getPayload());
        assertEquals(expected.getRequestId(), result.getRequestId());
    }
}
