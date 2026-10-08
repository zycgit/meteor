/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;

public final class RegistryStatus {
    private boolean reachable = false;
    private long    checkedAt = 0;
    private String  error     = null;

    public boolean isReachable() {
        return this.reachable;
    }

    public void setReachable(boolean reachable) {
        this.reachable = reachable;
    }

    public long getCheckedAt() {
        return this.checkedAt;
    }

    public void setCheckedAt(long checkedAt) {
        this.checkedAt = checkedAt;
    }

    public String getError() {
        return this.error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
