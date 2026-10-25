/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.complex;
import java.io.IOException;
import java.io.InputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 语言无关的第二层公共向量；每次读取创建全新的业务对象。 */
public final class ComplexValueVectors {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ComplexValueVectors() {
    }

    public static ComplexDocument document() throws IOException {
        return JSON.treeToValue(vector("document"), ComplexDocument.class);
    }

    public static ComplexIndex index() throws IOException {
        return JSON.treeToValue(vector("index"), ComplexIndex.class);
    }

    private static JsonNode vector(String name) throws IOException {
        try (InputStream input = ComplexValueVectors.class.getResourceAsStream("/vectors/complex/values.json")) {
            if (input == null) {
                throw new IOException("Missing shared complex value vectors");
            }
            return JSON.readTree(input).required(name);
        }
    }
}
