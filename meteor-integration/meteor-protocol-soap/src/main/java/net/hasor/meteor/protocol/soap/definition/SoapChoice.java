/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.util.List;

/** SOAP 包装体中一个 XML Schema choice 的约束。 */
public final class SoapChoice {
    private final int           minOccurs;
    private final List<Integer> partIndexes;

    SoapChoice(int minOccurs, List<Integer> partIndexes) {
        this.minOccurs = minOccurs;
        this.partIndexes = List.copyOf(partIndexes);
    }

    public int minOccurs() {
        return this.minOccurs;
    }

    public List<Integer> partIndexes() {
        return this.partIndexes;
    }
}
