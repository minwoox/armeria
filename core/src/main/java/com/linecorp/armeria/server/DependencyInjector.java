package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;

public interface DependencyInjector {

    @Nullable
    <T> T getInstance(Class<T> type);

    default DependencyInjector orElse(DependencyInjector other) {
        requireNonNull(other, "other");
        if (this == other) {
            return this;
        }
        return null;
    }
}
