/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 业务实现只接触 Java 类型，不处理 Triple wrapper 或 HTTP 报文。 */
public class TripleDemoServiceImpl implements TripleDemoService {
    private final AtomicInteger calls    = new AtomicInteger();
    private final AtomicInteger accepted = new AtomicInteger();

    public TripleMessage echo(TripleMessage request) {
        this.calls.incrementAndGet();
        return TripleMessage.of(request.getText(), request.getNumber() + 1);
    }

    public String greet(String name, int times) {
        return name.repeat(times);
    }

    public String overloaded(String value) {
        return "text:" + value;
    }

    public String overloaded(int value) {
        return "number:" + value;
    }

    public List<TripleMessage> list(List<TripleMessage> requests) {
        return requests;
    }

    public void accept(TripleMessage request) {
        this.accepted.addAndGet(request.getNumber());
    }

    public TripleMessage fail(TripleMessage request) {
        throw new IllegalArgumentException("triple failure 中文");
    }

    public TripleMessage slow(TripleMessage request) {
        // 业务等待保持有界，调用期限由 Caller 与协议截止时间共同传递。
        try {
            Thread.sleep(request.getNumber());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return this.echo(request);
    }

    public int getCalls() {
        return this.calls.get();
    }

    public int getAccepted() {
        return this.accepted.get();
    }
}
