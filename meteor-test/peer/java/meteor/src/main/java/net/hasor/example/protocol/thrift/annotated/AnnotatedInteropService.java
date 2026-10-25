/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.annotated;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;

/** 契约来自注解，与生成接口没有继承或类型引用关系。 */
public interface AnnotatedInteropService {
    String echo(@MetParam(id = 1) String value);

    @MetMethod(name = "add")
    int sum(@MetParam(id = 7) int left, @MetParam(id = 2) int right);

    AnnotatedSample mirror(@MetParam(id = 1) AnnotatedSample value);

    List<String> echoList(@MetParam(id = 1) List<String> value);

    Map<String, Integer> echoMap(@MetParam(id = 1) Map<String, Integer> value);

    Set<Long> echoSet(@MetParam(id = 1) Set<Long> value);

    byte[] echoBinary(@MetParam(id = 1) byte[] value);

    AnnotatedShade echoEnum(@MetParam(id = 1) AnnotatedShade value);

    AnnotatedChoice echoUnion(@MetParam(id = 1) AnnotatedChoice value);

    UUID echoUuid(@MetParam(id = 1) UUID value);

    AnnotatedSample defaults();

    void ping();

    String fail(@MetParam(id = 1) String reason) throws AnnotatedFailure;

    String crash(@MetParam(id = 1) String reason);

    @MetMessage
    void notify(@MetParam(id = 1) String value);

    int notificationCount();

    @MetMethod(name = "extended")
    String renamed(@MetParam(id = 1) String value);
}
