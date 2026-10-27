package io.github.tortaje.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Entry point for fixtures. The first call initializes OpenTelemetry (once, never throwing);
 * if initialization fails or is disabled, every method returns no-ops.
 */
public final class OtelFixtures {

    private static final String INSTRUMENTATION_SCOPE = "io.github.tortaje.otel";
    private static final Object LOCK = new Object();

    private static volatile OtelRuntime runtime;
    private static volatile Map<String, String> defaults = Map.of();
    private static volatile boolean registerShutdownHook = true;

    private OtelFixtures() {
    }

    /**
     * Tracer for fixture spans, to wrap outgoing calls ({@code SpanKind.CLIENT}).
     *
     * @return the tracer, or a no-op tracer when telemetry is disabled
     */
    public static Tracer tracer() {
        return openTelemetry().getTracer(INSTRUMENTATION_SCOPE);
    }

    /**
     * The OpenTelemetry instance initialized for fixtures, for uses beyond {@link #tracer()}.
     *
     * @return the instance, or a no-op instance when telemetry is disabled
     */
    public static OpenTelemetry openTelemetry() {
        return runtime().openTelemetry();
    }

    /**
     * Injects the current context ({@code traceparent} header by default) into an outgoing request.
     *
     * @param headerSetter called with {@code (name, value)} for each header to add, e.g. {@code requestBuilder::header}
     */
    public static void inject(BiConsumer<String, String> headerSetter) {
        openTelemetry().getPropagators().getTextMapPropagator()
                .inject(Context.current(), headerSetter, BiConsumer::accept);
    }

    /**
     * Whether telemetry is active.
     *
     * @return {@code false} when initialization failed or {@code OTEL_SDK_DISABLED=true}
     */
    public static boolean isEnabled() {
        return runtime().enabled();
    }

    private static OtelRuntime runtime() {
        OtelRuntime current = runtime;
        if (current == null) {
            synchronized (LOCK) {
                current = runtime;
                if (current == null) {
                    current = OtelBootstrap.start(Config.fromSystem(defaults), registerShutdownHook);
                    runtime = current;
                }
            }
        }
        return current;
    }

    /** For tests: stops the current instance and prepares a fresh initialization, without a shutdown hook. */
    static void resetForTest(Map<String, String> testDefaults) {
        synchronized (LOCK) {
            if (runtime != null) {
                runtime.shutdown().run();
            }
            runtime = null;
            GlobalOpenTelemetry.resetForTest();
            defaults = Map.copyOf(testDefaults);
            registerShutdownHook = false;
        }
    }
}
