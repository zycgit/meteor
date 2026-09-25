/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf;
import java.nio.file.Path;
import io.netty.util.TimerTask;
import net.hasor.cobble.bus.BusContext;
import net.hasor.rsf.serialize.SerializeCoder;

public interface RsfEnvironment extends AutoCloseable {
    RsfSettings getSettings();

    ClassLoader getClassLoader();

    BusContext getEventContext();

    Path getDataHome();

    SerializeCoder getSerializeCoder(String name);

    void atTime(TimerTask task, int timeout);

    void atTime(TimerTask task);

    String getInstanceID();

    void close();
}
