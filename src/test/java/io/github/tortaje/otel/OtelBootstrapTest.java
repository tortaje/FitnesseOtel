package io.github.tortaje.otel;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.GlobalOpenTelemetry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OtelBootstrapTest {

    @TempDir
    Path confDir;

    private OtelRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.shutdown().run();
        }
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void propertiesFileConfiguresTheSdk() throws IOException {
        ClassLoader loader = ConfigFileTest.classpathWith(confDir, """
                otel.service.name=from-file
                otel.traces.exporter=none
                otel.metrics.exporter=none
                otel.logs.exporter=none
                """);

        runtime = OtelBootstrap.start(Config.create(Map.of(), loader, key -> null, key -> null), false);

        assertThat(runtime.enabled()).isTrue();
        // the SDK's resource carries the service name read from the file
        assertThat(runtime.openTelemetry().toString()).contains("service.name=\"from-file\"");
    }
}
