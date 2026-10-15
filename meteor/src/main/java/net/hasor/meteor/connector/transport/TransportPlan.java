/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.BiFunction;
import net.hasor.cobble.ClassUtils;

/** Immutable prepared transport assembly. Creating it does not allocate runtime or network resources. */
public final class TransportPlan<M> {
    private final Class<? extends TransportPipeline>                                   pipelineType;
    private final BiFunction<TransportPipeline, TransportRuntimeServices, NetworkConnector<M>> creator;

    private TransportPlan(Class<? extends TransportPipeline> pipelineType, //
            BiFunction<TransportPipeline, TransportRuntimeServices, NetworkConnector<M>> creator) {
        this.pipelineType = pipelineType;
        this.creator = Objects.requireNonNull(creator, "creator");
    }

    /** Prepare a transport that does not use the socket pipeline extension. */
    public static <M> TransportPlan<M> direct(TransportConfig config, //
            BiFunction<TransportConfig, TransportRuntimeServices, NetworkConnector<M>> creator) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(creator, "creator");
        return new TransportPlan<>(null, (pipeline, runtimeServices) -> creator.apply(config, runtimeServices));
    }

    /** Resolve and validate a socket pipeline without initializing or instantiating its provider class. */
    public static <M> TransportPlan<M> withPipeline(TransportConfig config, ClassLoader loader, //
            BiFunction<TransportPipeline, TransportRuntimeServices, NetworkConnector<M>> creator) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(creator, "creator");
        Class<? extends TransportPipeline> pipelineType = resolvePipeline(config, loader);
        return new TransportPlan<>(pipelineType, creator);
    }

    /** Allocate one fresh provider and create the runtime transport for this activation attempt. */
    public NetworkConnector<M> create(TransportRuntimeServices runtimeServices) {
        Objects.requireNonNull(runtimeServices, "runtimeServices");
        TransportPipeline pipeline = this.pipelineType == null ? null : ClassUtils.newInstance(this.pipelineType);
        return this.creator.apply(pipeline, runtimeServices);
    }

    private static Class<? extends TransportPipeline> resolvePipeline(TransportConfig config, ClassLoader loader) {
        String name = config.option("transportPipeline", "");
        if (name.isEmpty()) {
            return null;
        }

        try {
            Class<? extends TransportPipeline> pipelineType = ClassUtils.getClass(loader, name, false) //
                    .asSubclass(TransportPipeline.class);
            Constructor<? extends TransportPipeline> constructor = pipelineType.getDeclaredConstructor();
            if (!Modifier.isPublic(pipelineType.getModifiers()) || !Modifier.isPublic(constructor.getModifiers())) {
                String message = "Transport pipeline requires a public no-argument constructor: " + name;
                throw new IllegalArgumentException(message);
            }
            return pipelineType;
        } catch (ClassNotFoundException failure) {
            throw new IllegalArgumentException("Unknown transport pipeline: " + name, failure);
        } catch (ClassCastException failure) {
            throw new IllegalArgumentException("Invalid transport pipeline: " + name, failure);
        } catch (NoSuchMethodException failure) {
            throw new IllegalArgumentException(//
                    "Transport pipeline requires a public no-argument constructor: " + name, failure);
        }
    }
}
