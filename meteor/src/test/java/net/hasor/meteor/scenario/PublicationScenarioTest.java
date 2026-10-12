/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetIgnore;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import static org.junit.Assert.*;

/** 发布身份、实现来源、本地优先和受限方法。 */
public class PublicationScenarioTest {
    @Test
    public void publishDifferentVersionsAndKeepLocalAndFixedTargetCallsDistinct() throws Exception {
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 一个接口发布两个版本，分别使用实现类和 Supplier。
            server.publisher().rsfService(Greeting.class).to(GreetingService.class).version("1").register();
            var second = server.publisher().rsfService(Greeting.class).toProvider(() -> value -> "v2:" + value);
            second.version("2").register();
            MetBindInfo<Greeting> one = client.publisher().rsfService(Greeting.class).version("1").register();
            var local = client.publisher().rsfService(Greeting.class, value -> "local:" + value);
            MetBindInfo<Greeting> two = local.version("2").register();
            server.start();
            client.start();

            // 相同服务 ID 可以在本机有实现；显式地址仍然调用远端。
            assertEquals("local:hello", client.getRsfClient().getRemote(two).greet("hello"));
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            assertEquals("v1:hello", remote.getRemote(one).greet("hello"));
            assertEquals("v2:hello", remote.getRemote(two).greet("hello"));
            assertNotEquals(one.getBindID(), two.getBindID());
        }
    }

    @Test
    public void ignoredMethodsRemainUnavailableToRemoteCallers() throws Exception {
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 服务端契约隐藏管理方法；客户端契约故意不带注解，验证远端准入。
            server.publisher().rsfService(PublicGreeting.class, value -> "visible:" + value).name("safe").register();
            MetBindInfo<UnrestrictedGreeting> service = client.publisher().rsfService(UnrestrictedGreeting.class).name("safe").register();
            server.start();
            client.start();
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            assertEquals("visible:hi", remote.getRemote(service).greet("hi"));
            var failure = remote.asyncInvoke(service, "internal", new Class<?>[0], new Object[0]);
            ExecutionException exception = assertThrows(ExecutionException.class, () -> failure.getData(5, TimeUnit.SECONDS));
            assertEquals(ProtocolStatus.Forbidden, ((MetException) exception.getCause()).getStatus());
            assertEquals("visible:next", remote.getRemote(service).greet("next"));
        }
    }

    public interface Greeting {
        String greet(String value);
    }

    public interface PublicGreeting extends Greeting {
        @MetIgnore
        default String internal() {
            return "not published";
        }
    }

    public interface UnrestrictedGreeting extends Greeting {
        String internal();
    }

    public static class GreetingService implements Greeting {
        public String greet(String value) {
            return "v1:" + value;
        }
    }
}
