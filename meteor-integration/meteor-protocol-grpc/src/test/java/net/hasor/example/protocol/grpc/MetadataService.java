/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.grpc;
import java.util.concurrent.Flow;

public interface MetadataService {
    Message echo(Message request);

    Flow.Publisher<Message> list(Message request);

    Message collect(Flow.Publisher<Message> requests);

    Flow.Publisher<Message> exchange(Flow.Publisher<Message> requests);

    Message fail(Message request);

    Flow.Publisher<Message> failStream(Message request);
}
