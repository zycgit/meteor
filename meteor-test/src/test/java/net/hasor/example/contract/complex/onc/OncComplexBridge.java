/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.complex.onc;
import net.hasor.example.contract.complex.ComplexDocument;
import net.hasor.example.contract.complex.ComplexIndex;
import net.hasor.example.contract.complex.ComplexValueService;

/** ONC 入口只转发调用，实际嵌套类型处理使用同一份业务实现。 */
public final class OncComplexBridge implements OncComplexService {
    private final ComplexValueService service;

    public OncComplexBridge(ComplexValueService service) {
        this.service = service;
    }

    @Override
    public ComplexDocument acceptDocument(ComplexDocument request) {
        return this.service.acceptDocument(request);
    }

    @Override
    public ComplexIndex acceptIndex(ComplexIndex request) {
        return this.service.acceptIndex(request);
    }
}
