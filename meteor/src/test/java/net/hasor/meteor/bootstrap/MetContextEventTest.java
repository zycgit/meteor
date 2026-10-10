/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetContextEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetContextEventTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void reportsCommittedLifecycleAndServiceEventsAndIsolatesListeners() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        configuration.setAutomaticOnline(false);
        try (MetContext context = configuration.buildContext()) {
            List<MetContextEvent> events = new ArrayList<>();
            Cancellable broken = context.subscribe(event -> {
                throw new IllegalStateException("listener failure");
            });
            context.subscribe(events::add);
            context.publisher().rsfService(Echo.class).register();
            context.start();
            context.start();
            context.online();
            context.online();
            context.offline();
            context.offline();
            context.publisher().rsfService(Echo.class).name("late").register();
            assertTrue(broken.cancel());
            assertFalse(broken.cancel());
            context.close();
            context.close();
            List<MetContextEvent> expected = List.of(MetContextEvent.SERVICES_CHANGED, MetContextEvent.STARTED, MetContextEvent.ONLINE, MetContextEvent.OFFLINE, MetContextEvent.SERVICES_CHANGED, MetContextEvent.CLOSED);
            assertEquals(expected, events);
            assertThrows(IllegalStateException.class, () -> context.subscribe(events::add));
        }
    }

    @Test
    public void cancellingOneRegistrationDoesNotRemoveAnotherForTheSameListener() throws Exception {
        try (MetContext context = new MetConfiguration().buildContext()) {
            List<MetContextEvent> events = new ArrayList<>();
            Consumer<MetContextEvent> listener = events::add;
            Cancellable first = context.subscribe(listener);
            Cancellable second = context.subscribe(listener);
            assertTrue(first.cancel());
            assertFalse(first.cancel());
            context.publisher().rsfService(Echo.class).register();
            assertEquals(List.of(MetContextEvent.SERVICES_CHANGED), events);
            assertTrue(second.cancel());
        }
    }

}
