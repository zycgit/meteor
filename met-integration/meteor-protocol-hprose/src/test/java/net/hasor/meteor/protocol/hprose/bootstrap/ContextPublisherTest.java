/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.hasor.meteor.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextPublisherTest extends BootstrapTestSupport {

    @Test
    public void allPublisherOverloadsApplyHproseDefaultsBeforeRegistration() throws Exception {
        MetContext runtime = this.context(this.configuration());
        MetPublisher publisher = runtime.publisher();
        assertSame(runtime.getSettings(), publisher.getSettings());
        MetFilter filter = (request, response, chain) -> chain.doFilter(request, response);
        assertSame(publisher, publisher.bindFilter("test", filter));
        Supplier<Echo> provider = EchoService::new;
        List<MetBindInfo<Echo>> services = Arrays.asList(publisher.rsfService(Echo.class).name("consumer").register(), publisher.rsfService(Echo.class, new EchoService()).name("instance").register(), publisher.rsfService(Echo.class, EchoService.class).name("class").register(), publisher.rsfService(Echo.class, provider).name("supplier").register());
        for (MetBindInfo<Echo> service : services) {
            assertEquals("echo", service.getAliasName("Hprose"));
        }
        assertEquals(Collections.singletonList("echo"), runtime.getServiceIDs("Hprose"));
        assertEquals(services.get(0).getBindID(), runtime.getServiceInfo("Hprose", "echo").getBindID());
    }

    @Test
    public void registrationQueriesAndLocalCallsShareTheContainerBeforeStart() throws Exception {
        MetContext context = this.context(this.configuration());
        EchoService instance = new EchoService();
        MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, instance).register();
        assertEquals(service.getBindID(), context.getServiceInfo(Echo.class).getBindID());
        assertEquals(service.getBindID(), context.getServiceInfo(service.getBindID()).getBindID());
        assertEquals(service.getBindID(), context.getServiceInfo(service.getBindGroup(), service.getBindName(), service.getBindVersion()).getBindID());
        assertEquals(service.getBindID(), context.getServiceInfo("Hprose", "echo").getBindID());
        assertSame(instance, context.getServiceProvider(service).get());
        service.setMetaData("tag", "value");
        assertEquals("value", context.getServiceInfo(service.getBindID()).getMetaData("tag"));
        service.removeMetaData("tag");
        assertNull(service.getMetaData("tag"));
        assertThrows(IllegalStateException.class, () -> context.publisher().rsfService(Echo.class, new EchoService()).register());
        assertEquals(Collections.singletonList(service.getBindID()), context.getServiceIDs());
        assertEquals(Collections.singletonList("echo"), context.getServiceIDs("Hprose"));
        assertEquals("local", context.getRsfClient().getRemote(service).echo("local"));
        assertFalse(context.isOnline());
        assertTrue(context.runProtocols().isEmpty());
        assertNull(context.bindAddress("rsf"));
        assertThrows(IllegalStateException.class, context::online);
    }

    @Test
    public void filterCanCompleteAnOutboundCallWithoutContinuingTheChain() throws Exception {
        MetContext context = this.context(this.configuration());
        context.publisher().bindFilter("cached", (request, response, chain) -> response.sendData("cached"));
        MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
        MetFuture call = context.getRsfClient().asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "value" });
        assertTrue(call.isDone());
        assertEquals("cached", call.getData(1, TimeUnit.SECONDS));
        assertTrue(context.runProtocols().isEmpty());
    }
}
