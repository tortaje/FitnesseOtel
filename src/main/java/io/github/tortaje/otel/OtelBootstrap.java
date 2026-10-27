package io.github.tortaje.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Builds the SDK, registers it globally, starts JVM metrics and installs the JUL handler. Never throws. */
final class OtelBootstrap {

    private static final Logger LOG = Logger.getLogger("io.github.tortaje.otel");

    /** Java autoconfigure defaults to gRPC; only the JDK HTTP sender is bundled. */
    private static final Map<String, String> BUILT_IN_DEFAULTS = Map.of("otel.exporter.otlp.protocol", "http/protobuf");

    private OtelBootstrap() {
    }

    private static Map<String, String> withBuiltInDefaults(Map<String, String> defaults) {
        Map<String, String> merged = new HashMap<>(BUILT_IN_DEFAULTS);
        merged.putAll(defaults);
        return merged;
    }

    static OtelRuntime start(Config config, boolean registerShutdownHook) {
        if (config.sdkDisabled()) {
            LOG.info("fitnesse-otel: SDK disabled (otel.sdk.disabled=true)");
            return OtelRuntime.noop();
        }
        OpenTelemetrySdk sdk = null;
        RuntimeTelemetry runtimeTelemetry = null;
        OtelJulHandler handler = null;
        try {
            sdk = AutoConfiguredOpenTelemetrySdk.builder()
                    .addPropertiesSupplier(() -> withBuiltInDefaults(config.defaults()))
                    .disableShutdownHook() // replaced by our own time-bounded hook
                    .build()
                    .getOpenTelemetrySdk();
            OpenTelemetry openTelemetry = sdk;
            try {
                GlobalOpenTelemetry.set(sdk);
            } catch (IllegalStateException alreadySet) {
                sdk.shutdown();
                sdk = null;
                openTelemetry = GlobalOpenTelemetry.get();
                LOG.warning("fitnesse-otel: GlobalOpenTelemetry instance already set, reusing it");
            }
            runtimeTelemetry = RuntimeTelemetry.create(openTelemetry);
            handler = new OtelJulHandler(openTelemetry.getLogsBridge(), config.logLevel());
            Logger.getLogger("").addHandler(handler);

            Runnable shutdown = new Shutdown(handler, runtimeTelemetry, sdk, config.shutdownTimeout());
            if (registerShutdownHook) {
                Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "fitnesse-otel-shutdown"));
            }
            return new OtelRuntime(openTelemetry, true, shutdown);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "fitnesse-otel: initialization failed, telemetry disabled", t);
            new Shutdown(handler, runtimeTelemetry, sdk, Duration.ZERO).run();
            return OtelRuntime.noop();
        }
    }

    /** Removes the handler, flushes and closes the owned SDK within a bounded time, then closes JVM metrics. */
    private static final class Shutdown implements Runnable {

        private final AtomicBoolean done = new AtomicBoolean();
        private final OtelJulHandler handler;
        private final RuntimeTelemetry runtimeTelemetry;
        private final OpenTelemetrySdk ownedSdk;
        private final Duration timeout;

        Shutdown(OtelJulHandler handler, RuntimeTelemetry runtimeTelemetry, OpenTelemetrySdk ownedSdk,
                Duration timeout) {
            this.handler = handler;
            this.runtimeTelemetry = runtimeTelemetry;
            this.ownedSdk = ownedSdk;
            this.timeout = timeout;
        }

        @Override
        public void run() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            if (handler != null) {
                Logger.getLogger("").removeHandler(handler);
            }
            // SDK first: its final metric collection must still see the JVM instruments
            if (ownedSdk != null) {
                shutdownWithin(ownedSdk, timeout);
            }
            try {
                if (runtimeTelemetry != null) {
                    runtimeTelemetry.close();
                }
            } catch (RuntimeException ignored) {
                // shutdown goes on
            }
        }

        /**
         * {@code sdk.shutdown()} itself blocks (final metric collection): it runs on a daemon thread
         * and we wait no longer than {@code timeout}, then give up.
         */
        private static void shutdownWithin(OpenTelemetrySdk sdk, Duration timeout) {
            CountDownLatch finished = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                try {
                    sdk.shutdown().join(timeout.toSeconds(), TimeUnit.SECONDS);
                } catch (RuntimeException ignored) {
                    // shutdown must never fail
                } finally {
                    finished.countDown();
                }
            }, "fitnesse-otel-sdk-shutdown");
            worker.setDaemon(true);
            worker.start();
            try {
                finished.await(timeout.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
