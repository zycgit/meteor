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
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetService;

/** 程序号与过程号对应 vectors/complex/values.x，业务签名仍由共享契约持有。 */
@MetService(id = 0x31234892L)
public interface OncComplexService extends ComplexValueService {
    @Override
    @MetMethod(id = 1)
    ComplexDocument acceptDocument(ComplexDocument request);

    @Override
    @MetMethod(id = 2)
    ComplexIndex acceptIndex(ComplexIndex request);
}
