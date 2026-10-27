package io.github.tortaje.otel;

import io.opentelemetry.api.OpenTelemetry;

/** Initialization result: the instance to use, its state and the (idempotent) shutdown procedure. */
record OtelRuntime(OpenTelemetry openTelemetry, boolean enabled, Runnable shutdown) {

    static OtelRuntime noop() {
        return new OtelRuntime(OpenTelemetry.noop(), false, () -> { });
    }
}
