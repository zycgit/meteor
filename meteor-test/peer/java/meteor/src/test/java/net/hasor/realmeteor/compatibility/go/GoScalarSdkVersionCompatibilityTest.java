/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.compatibility.go;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.example.echo.TextEchoService;
import net.hasor.example.protocol.golang.GoEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.realmeteor.environment.golang.GoPeerProcess;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** 固定真实 Go SDK 发布版本，分别验证 Unicode 回声和业务异常的双向线格式。 */
@RunWith(Parameterized.class)
public class GoScalarSdkVersionCompatibilityTest {
    private final String protocol;
    private final String direction;
    private final String executable;

    public GoScalarSdkVersionCompatibilityTest(String protocol, String sdk, String version, String profile,
            String direction, String executable) {
        this.protocol = protocol;
        this.direction = direction;
        this.executable = executable;
    }

    @Parameterized.Parameters(name = "sdk/go/{0}/{1}@{2}/{3}/{4}")
    public static Collection<Object[]> versions() {
        // 顺序按协议与 SDK 大版本排列；每个方向形成独立 JUnit 实例。
        List<Object[]> versions = new ArrayList<>();
        add(versions, "hprose", "Hprose-Go", "2.0.6+incompatible", "http1-hprose", "hprose-2.0.6-peer");
        add(versions, "hprose", "Hprose-Go", "3.0.16", "http1-hprose", "hprose-3.0.16-peer");
        add(versions, "thrift", "Apache-Thrift", "0.13.0", "binary-framed", "thrift-0.13.0-peer");
        add(versions, "thrift", "Apache-Thrift", "0.14.2", "binary-framed", "thrift-0.14.2-peer");
        add(versions, "thrift", "Apache-Thrift", "0.18.1", "binary-framed", "thrift-0.18.1-peer");
        add(versions, "thrift", "Apache-Thrift", "0.25.0", "binary-framed", "thrift-0.25.0-peer");
        add(versions, "dubbo", "Apache-Dubbo-Go", "1.5.9", "dubbo-hessian2", "dubbo-1.5.9-peer");
        add(versions, "dubbo", "Apache-Dubbo-Go", "3.3.2", "dubbo-hessian2", "../dubbo-peer");
        return versions;
    }

    private static void add(List<Object[]> rows, String protocol, String sdk, String version, String profile,
            String binary) {
        String executable = "/opt/meteor-peer/go/compatibility/" + binary;
        rows.add(new Object[] { protocol, sdk, version, profile, "SDK-to-Meteor", executable });
        rows.add(new Object[] { protocol, sdk, version, profile, "Meteor-to-SDK", executable });
    }

    @Test(timeout = 45000)
    public void unicodeScalarRoundTrip() throws Exception {
        // SDK 版本由独立二进制决定，方向由测试参数公开显示。
        boolean sdkConsumer = StringUtils.equals("SDK-to-Meteor", this.direction);
        MetConfiguration configuration = this.configuration(sdkConsumer);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<?> binding = this.publish(context, sdkConsumer);
            String target = sdkConsumer ? this.target(context) : null;
            String role = sdkConsumer ? "client" : "server";

            // 对端只拥有当前场景进程；所有请求都经过实际网络与官方 SDK。
            try (GoPeerProcess peer = this.peer(role, "unicode", target)) {
                if (sdkConsumer) {
                    JsonNode outcome = peer.awaitResult();
                    assertTrue(outcome.toString(), outcome.path("ok").asBoolean());
                } else {
                    URI endpoint = URI.create(peer.awaitReady());
                    String authority = endpoint.getAuthority();
                    InterAddress address = new InterAddress(this.protocol + "://" + authority + "/default");
                    String request = "Meteor → Go 中文🙂";
                    Object response = context.getRsfClient(address).asyncInvoke(binding, "echo", //
                            new Class<?>[] { String.class }, new Object[] { request }).getData(10, TimeUnit.SECONDS);
                    assertEquals(request + "|go-" + this.protocol, response);
                }
            }
        }
    }

    @Test(timeout = 45000)
    public void applicationFailureRemainsReadable() throws Exception {
        // 业务错误使用固定标识，不能用连接失败或超时冒充异常兼容。
        boolean sdkConsumer = StringUtils.equals("SDK-to-Meteor", this.direction);
        MetConfiguration configuration = this.configuration(sdkConsumer);
        try (MetContext context = configuration.buildContext()) {
            context.start();
            MetBindInfo<?> binding = this.publish(context, sdkConsumer);
            String target = sdkConsumer ? this.target(context) : null;
            String role = sdkConsumer ? "client" : "server";
            try (GoPeerProcess peer = this.peer(role, "error", target)) {
                if (sdkConsumer) {
                    JsonNode outcome = peer.awaitResult();
                    assertTrue(outcome.toString(), outcome.path("ok").asBoolean());
                } else {
                    URI endpoint = URI.create(peer.awaitReady());
                    String authority = endpoint.getAuthority();
                    InterAddress address = new InterAddress(this.protocol + "://" + authority + "/default");
                    var pending = context.getRsfClient(address).asyncInvoke(binding, "echo", //
                            new Class<?>[] { String.class }, new Object[] { "fail" });
                    ExecutionException failure = assertThrows(ExecutionException.class, //
                            () -> pending.getData(10, TimeUnit.SECONDS));
                    String marker = "fail";
                    if (StringUtils.equals("hprose", this.protocol)) {
                        marker = "version-peer-error";
                    } else if (StringUtils.equals("dubbo", this.protocol)) {
                        marker = "compat-error";
                    }
                    assertTrue(failure.toString(), failure.getCause().getMessage().contains(marker));
                }
            }
        }
    }

    private MetConfiguration configuration(boolean listening) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol(this.protocol);
        configuration.setDefaultTimeout(10000);
        boolean http = StringUtils.equals("hprose", this.protocol);
        var connector = configuration.connector(http ? "http" : "tcp");
        if (listening) {
            connector.bind(ContainerProcess.runnerHost(), 0);
        }
        var mount = connector.protocol(this.protocol);
        if (http) {
            connector.option("httpVersion", 1);
            mount.contextPath("/rpc");
        }
        if (StringUtils.equals("thrift", this.protocol)) {
            mount.option("encoding", "binary").option("framed", true);
        }
        return configuration;
    }

    private MetBindInfo<?> publish(MetContext context, boolean provider) {
        if (StringUtils.equals("hprose", this.protocol)) {
            GoEchoService implementation = provider ? GoScalarSdkVersionCompatibilityTest::echo : null;
            return context.publisher().rsfService(GoEchoService.class, implementation) //
                    .protocol("hprose").aliasName("Hprose", "sample").register();
        }
        TextEchoService implementation = provider ? GoScalarSdkVersionCompatibilityTest::echo : null;
        var builder = context.publisher().rsfService(TextEchoService.class, implementation).protocol(this.protocol);
        if (StringUtils.equals("dubbo", this.protocol)) {
            builder.group("compat").version("1.0").aliasName("dubbo", "meteor.example.GoEcho").serialize("Hessian2");
        } else {
            builder.aliasName("thrift", "Echo");
        }
        return builder.register();
    }

    private static String echo(String value) {
        if (StringUtils.equals("fail", value)) {
            throw new IllegalStateException("compat-error: version-peer-error: fail");
        }
        return value + "|meteor";
    }

    private String target(MetContext context) {
        String authority = context.getListenAddress(this.protocol).getHostPort();
        if (StringUtils.equals("hprose", this.protocol)) {
            return "http://" + authority + "/rpc";
        }
        return authority;
    }

    private GoPeerProcess peer(String role, String scenario, String target) throws Exception {
        String selected = StringUtils.equals("thrift", this.protocol) ? "thrift-binary-framed" : this.protocol;
        return GoPeerProcess.startBinary(this.executable, selected, role, "plain", scenario, target);
    }
}
