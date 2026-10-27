package io.github.tortaje.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtelFixturesTest {

    private static final Map<String, String> NONE = Map.of(
            "otel.traces.exporter", "none",
            "otel.metrics.exporter", "none",
            "otel.logs.exporter", "none");

    @BeforeEach
    void setUp() {
        OtelFixtures.resetForTest(NONE);
    }

    @AfterEach
    void tearDown() {
        OtelFixtures.resetForTest(Map.of());
    }

    private static Map<String, String> none(String key, String value) {
        Map<String, String> defaults = new HashMap<>(NONE);
        defaults.put(key, value);
        return defaults;
    }

    private static long installedHandlers() {
        return Arrays.stream(Logger.getLogger("").getHandlers()).filter(OtelJulHandler.class::isInstance).count();
    }

    @Test
    void initializesLazilyAndInstallsOneHandler() {
        assertThat(installedHandlers()).isZero();

        assertThat(OtelFixtures.isEnabled()).isTrue();

        assertThat(installedHandlers()).isEqualTo(1);
    }

    @Test
    void concurrentFirstCallsInitializeOnce() throws Exception {
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        Set<OpenTelemetry> instances = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    OtelFixtures.tracer();
                    instances.add(OtelFixtures.openTelemetry());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(instances).hasSize(1);
        assertThat(installedHandlers()).isEqualTo(1);
    }

    @Test
    void injectWritesTraceparent() {
        // with exporter=none, spans from OtelFixtures.tracer() are no-op (no active processor):
        // the span comes from a test tracer, only OtelFixtures propagation is checked here
        Map<String, String> headers = new HashMap<>();
        Span span = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
                .build().get("test").spanBuilder("GET /orders").startSpan();
        assertThat(span.getSpanContext().isValid()).isTrue();
        try (Scope ignored = span.makeCurrent()) {
            OtelFixtures.inject(headers::put);
        } finally {
            span.end();
        }

        assertThat(headers.get("traceparent")).isEqualTo(
                "00-" + span.getSpanContext().getTraceId() + "-" + span.getSpanContext().getSpanId()
                        + "-" + span.getSpanContext().getTraceFlags().asHex());
    }

    @Test
    void defaultsToOtlpHttpProtobuf() {
        // without an explicit protocol, Java autoconfigure would pick gRPC, for which no sender is bundled
        Map<String, String> otlpWithoutProtocol = new HashMap<>(NONE);
        otlpWithoutProtocol.put("otel.traces.exporter", "otlp");
        otlpWithoutProtocol.put("otel.exporter.otlp.endpoint", "http://localhost:1");
        OtelFixtures.resetForTest(otlpWithoutProtocol);

        assertThat(OtelFixtures.isEnabled()).isTrue();
    }

    @Test
    void sdkDisabledGivesNoop() {
        OtelFixtures.resetForTest(none("otel.sdk.disabled", "true"));

        assertThat(OtelFixtures.isEnabled()).isFalse();
        assertThat(OtelFixtures.tracer().spanBuilder("s").startSpan().getSpanContext().isValid()).isFalse();
        assertThat(installedHandlers()).isZero();
    }

    @Test
    void brokenConfigGivesNoopAndSingleWarn() {
        OtelFixtures.resetForTest(none("otel.traces.exporter", "doesnotexist"));
        try (CapturedWarnings captured = new CapturedWarnings()) {
            OtelFixtures.tracer();
            OtelFixtures.tracer();
            OtelFixtures.tracer();

            assertThat(OtelFixtures.isEnabled()).isFalse();
            assertThat(captured.warnings()).hasSize(1);
        }
    }

    @Test
    void reusesExistingGlobalWithWarn() {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        OpenTelemetrySdk existing = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
                .build();
        GlobalOpenTelemetry.set(existing);
        try (CapturedWarnings captured = new CapturedWarnings()) {
            OtelFixtures.tracer().spanBuilder("reused").startSpan().end();

            assertThat(spans.getFinishedSpanItems()).extracting(s -> s.getName()).containsExactly("reused");
            assertThat(captured.warnings()).hasSize(1);
        } finally {
            existing.close();
        }
    }

    @Test
    void earlyGlobalGetDoesNotBreak() {
        GlobalOpenTelemetry.get();

        assertThatCode(OtelFixtures::tracer).doesNotThrowAnyException();
    }

    @Test
    void shutdownRemovesHandlerAndIsIdempotent() {
        OtelFixtures.tracer();
        assertThat(installedHandlers()).isEqualTo(1);

        assertThatCode(() -> {
            OtelFixtures.resetForTest(NONE);
            OtelFixtures.resetForTest(NONE);
        }).doesNotThrowAnyException();

        assertThat(installedHandlers()).isZero();
        assertThatCode(() -> Logger.getLogger("x").info("after")).doesNotThrowAnyException();
    }
}
