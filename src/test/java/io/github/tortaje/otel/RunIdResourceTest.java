package io.github.tortaje.otel;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunIdResourceTest {

    private static final DefaultConfigProperties EMPTY_CONFIG = DefaultConfigProperties.createFromMap(Map.of());

    private static String runIdFrom(Map<String, String> env) {
        Resource resource = new RunIdResource(env::get).createResource(EMPTY_CONFIG);
        return resource.getAttribute(RunIdResource.TEST_RUN_ID);
    }

    @Test
    void usesTestRunIdEnv() {
        assertThat(runIdFrom(Map.of("TEST_RUN_ID", "ci-123"))).isEqualTo("ci-123");
    }

    @Test
    void generatesStableUuid() {
        String first = runIdFrom(Map.of());
        String second = runIdFrom(Map.of());

        assertThat(first).isEqualTo(second);
        assertThat(UUID.fromString(first).toString()).isEqualTo(first);
    }

    @Test
    void blankEnvFallsBackToUuid() {
        String runId = runIdFrom(Map.of("TEST_RUN_ID", "  "));

        assertThat(UUID.fromString(runId).toString()).isEqualTo(runId);
    }

    @Test
    void otelResourceAttributesWins() {
        assertThat(autoconfiguredRunId(Map.of("otel.resource.attributes", "test.run.id=from-otel")))
                .isEqualTo("from-otel");
    }

    @Test
    void isDiscoveredBySpi() {
        assertThat(autoconfiguredRunId(Map.of())).isNotBlank();
    }

    /** Builds an SDK through autoconfigure, emits a span and reads test.run.id from its resource. */
    private static String autoconfiguredRunId(Map<String, String> extraProperties) {
        Map<String, String> properties = new HashMap<>(Map.of(
                "otel.traces.exporter", "none",
                "otel.metrics.exporter", "none",
                "otel.logs.exporter", "none"));
        properties.putAll(extraProperties);
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        var sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> properties)
                .addTracerProviderCustomizer((builder, config) ->
                        builder.addSpanProcessor(SimpleSpanProcessor.create(spans)))
                .build()
                .getOpenTelemetrySdk();
        try {
            sdk.getTracer("test").spanBuilder("s").startSpan().end();
            return spans.getFinishedSpanItems().get(0).getResource().getAttribute(RunIdResource.TEST_RUN_ID);
        } finally {
            sdk.close();
        }
    }
}
