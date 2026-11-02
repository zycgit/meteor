/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.mapping;
import java.util.concurrent.atomic.AtomicInteger;

public class NoteServiceImpl implements NoteService {
    private final AtomicInteger notifications = new AtomicInteger();

    public NoteServiceImpl() {
    }

    @Override
    public Note echo(Note value) { return value; }

    @Override
    public int subtract(int left, int right) { return left - right; }

    @Override
    public void fail(int code) throws NoteFailure {
        NoteFailure failure = new NoteFailure();
        failure.setCode(code);
        throw failure;
    }

    @Override
    public void notify(String text) { this.notifications.incrementAndGet(); }

    @Override
    public int notificationCount() { return this.notifications.get(); }
}
