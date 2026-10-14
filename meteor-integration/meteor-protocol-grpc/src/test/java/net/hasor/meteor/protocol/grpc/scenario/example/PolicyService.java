/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario.example;
import java.util.concurrent.Flow;

public interface PolicyService {
    Message echo(Message input);

    Flow.Publisher<Message> list(Message input);

    Message collect(Flow.Publisher<Message> input);

    Flow.Publisher<Message> exchange(Flow.Publisher<Message> input);
}
