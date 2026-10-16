/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap.codec;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 多 part 返回值的普通泛型 DTO 与显式属性方向样本。 */
public final class ResultMappingValues {
    private ResultMappingValues() {
    }

    public static class Pair<T> {
        private T first;
        private T second;

        public T getFirst() {
            return this.first;
        }

        public void setFirst(T first) {
            this.first = first;
        }

        public T getSecond() {
            return this.second;
        }

        public void setSecond(T second) {
            this.second = second;
        }
    }

    public static class GetterOnly {
        public String getFirst() {
            return "first";
        }

        public String getSecond() {
            return "second";
        }
    }

    public static class SetterOnly {
        private String firstValue;
        private String secondValue;

        public void setFirst(String first) {
            this.firstValue = first;
        }

        public void setSecond(String second) {
            this.secondValue = second;
        }

        public String firstValue() {
            return this.firstValue;
        }

        public String secondValue() {
            return this.secondValue;
        }
    }

    public static class WriteOnlyProperty extends Pair<String> {
        @Override
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public String getFirst() {
            return super.getFirst();
        }
    }

    public static class ReadOnlyProperty extends Pair<String> {
        @Override
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String getFirst() {
            return super.getFirst();
        }
    }
}
