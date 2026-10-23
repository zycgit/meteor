/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.composition.http.grpc;

/** 一个业务接口同时发布到 gRPC、gRPC-Web、Connect 和 Triple。 */
public interface GrpcFamilyService {
    GrpcFamilyMessage echo(GrpcFamilyMessage request);
}
