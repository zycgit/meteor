/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import net.hasor.meteor.annotation.MetService;

public final class ScannerTypes {
    private ScannerTypes() {
    }

    @MetService
    public interface Service {
        String echo(String value);
    }

    @MetService
    public static class AnnotatedClass {
    }

    public interface UnannotatedInterface {
    }
}
