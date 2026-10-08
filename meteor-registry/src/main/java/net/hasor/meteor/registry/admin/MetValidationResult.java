/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;

public final class MetValidationResult {
    private String[] errors = new String[0];

    public boolean isValid() {
        return this.errors.length == 0;
    }

    public String[] getErrors() {
        return this.errors;
    }

    public void setErrors(String[] errors) {
        this.errors = errors;
    }
}
