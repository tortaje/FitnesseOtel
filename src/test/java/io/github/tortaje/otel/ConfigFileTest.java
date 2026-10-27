package io.github.tortaje.otel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileTest {

    @TempDir
    Path confDir;

    /** A class loader whose classpath is a directory holding {@code fitnesse-otel.properties}, as with {@code !path conf}. */
    static ClassLoader classpathWith(Path dir, String content) throws IOException {
        Files.writeString(dir.resolve(Config.CONFIG_FILE), content, StandardCharsets.ISO_8859_1);
        return new URLClassLoader(new URL[] {dir.toUri().toURL()}, null);
    }

    private static Config config(ClassLoader loader, Map<String, String> defaults, Map<String, String> env) {
        return Config.create(defaults, loader, key -> null, env::get);
    }

    @Test
    void readsPropertiesFileFromClasspath() throws IOException {
        ClassLoader loader = classpathWith(confDir, "fitnesse.otel.log.level=WARNING\n");

        assertThat(config(loader, Map.of(), Map.of()).logLevel()).isEqualTo(Level.WARNING);
    }

    @Test
    void environmentBeatsFile() throws IOException {
        ClassLoader loader = classpathWith(confDir, "fitnesse.otel.log.level=WARNING\n");

        assertThat(config(loader, Map.of(), Map.of("FITNESSE_OTEL_LOG_LEVEL", "FINE")).logLevel())
                .isEqualTo(Level.FINE);
    }

    @Test
    void systemPropertyBeatsFile() throws IOException {
        ClassLoader loader = classpathWith(confDir, "fitnesse.otel.log.level=WARNING\n");
        Config config = Config.create(Map.of(), loader,
                key -> key.equals("fitnesse.otel.log.level") ? "SEVERE" : null, key -> null);

        assertThat(config.logLevel()).isEqualTo(Level.SEVERE);
    }

    @Test
    void fileEntriesBecomeAutoconfigureDefaults() throws IOException {
        ClassLoader loader = classpathWith(confDir,
                "otel.service.name=from-file\notel.exporter.otlp.endpoint=http://collector:4318\n");

        assertThat(config(loader, Map.of(), Map.of()).defaults())
                .containsEntry("otel.service.name", "from-file")
                .containsEntry("otel.exporter.otlp.endpoint", "http://collector:4318");
    }

    @Test
    void suppliedDefaultsBeatFile() throws IOException {
        ClassLoader loader = classpathWith(confDir, "otel.service.name=from-file\n");

        assertThat(config(loader, Map.of("otel.service.name", "from-defaults"), Map.of()).defaults())
                .containsEntry("otel.service.name", "from-defaults");
    }

    @Test
    void missingFileIsIgnoredSilently() {
        try (CapturedWarnings captured = new CapturedWarnings()) {
            Config config = config(new URLClassLoader(new URL[0], null), Map.of("a", "b"), Map.of());

            assertThat(config.defaults()).isEqualTo(Map.of("a", "b"));
            assertThat(captured.warnings()).isEmpty();
        }
    }

    @Test
    void malformedFileIsIgnoredWithOneWarn() throws IOException {
        ClassLoader loader = classpathWith(confDir, "otel.service.name=\\uZZZZ\n");
        try (CapturedWarnings captured = new CapturedWarnings()) {
            Config config = config(loader, Map.of(), Map.of());

            assertThat(config.defaults()).isEmpty();
            assertThat(captured.warnings()).hasSize(1);
        }
    }
}
