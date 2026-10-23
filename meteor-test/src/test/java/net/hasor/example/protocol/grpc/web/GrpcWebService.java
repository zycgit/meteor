/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.grpc.web;
import java.util.concurrent.Flow;
import net.hasor.example.protocol.grpc.Message;

/** gRPC-Web 支持普通调用与单请求的服务端响应流。 */
public interface GrpcWebService {
    Message echo(Message request);

    Flow.Publisher<Message> list(Message request);

    Message fail(Message request);

    Flow.Publisher<Message> broken(Message request);
}
