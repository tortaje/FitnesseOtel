package io.github.tortaje.otel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

class ConfigTest {

    private static Config config(Map<String, String> defaults, Map<String, String> sysProps, Map<String, String> env) {
        return new Config(defaults, sysProps::get, env::get);
    }

    @Test
    void sysPropBeatsEnvBeatsDefault() {
        Map<String, String> defaults = Map.of("fitnesse.otel.log.level", "SEVERE");
        Map<String, String> env = Map.of("FITNESSE_OTEL_LOG_LEVEL", "WARNING");
        Map<String, String> sysProps = Map.of("fitnesse.otel.log.level", "FINE");

        assertThat(config(defaults, sysProps, env).get("fitnesse.otel.log.level")).contains("FINE");
        assertThat(config(defaults, Map.of(), env).get("fitnesse.otel.log.level")).contains("WARNING");
        assertThat(config(defaults, Map.of(), Map.of()).get("fitnesse.otel.log.level")).contains("SEVERE");
    }

    @Test
    void blankValuesAreIgnored() {
        Map<String, String> defaults = Map.of("fitnesse.otel.log.level", "SEVERE");

        assertThat(config(defaults, Map.of(), Map.of("FITNESSE_OTEL_LOG_LEVEL", "  ")).get("fitnesse.otel.log.level"))
                .contains("SEVERE");
    }

    @Test
    void parsesLevelAndTimeout() {
        Config config = config(Map.of(), Map.of(),
                Map.of("FITNESSE_OTEL_LOG_LEVEL", "WARNING", "FITNESSE_OTEL_SHUTDOWN_TIMEOUT", "12"));

        assertThat(config.logLevel()).isEqualTo(Level.WARNING);
        assertThat(config.shutdownTimeout()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void defaultsWhenAbsent() {
        Config config = config(Map.of(), Map.of(), Map.of());

        assertThat(config.logLevel()).isEqualTo(Level.INFO);
        assertThat(config.shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void invalidValuesFallBackWithOneWarn() {
        try (CapturedWarnings captured = new CapturedWarnings()) {
            Config invalid = config(Map.of(), Map.of(),
                    Map.of("FITNESSE_OTEL_LOG_LEVEL", "abc", "FITNESSE_OTEL_SHUTDOWN_TIMEOUT", "-3"));
            assertThat(invalid.logLevel()).isEqualTo(Level.INFO);
            assertThat(invalid.shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(config(Map.of(), Map.of(), Map.of("FITNESSE_OTEL_SHUTDOWN_TIMEOUT", "x")).shutdownTimeout())
                    .isEqualTo(Duration.ofSeconds(5));

            assertThat(captured.warnings()).hasSize(3);
        }
    }

    @Test
    void numericLevelsAreRejectedWithWarn() {
        // the spec expects a JUL level name: "99999" would silently turn off all log export
        try (CapturedWarnings captured = new CapturedWarnings()) {
            assertThat(config(Map.of(), Map.of(), Map.of("FITNESSE_OTEL_LOG_LEVEL", "-3")).logLevel())
                    .isEqualTo(Level.INFO);
            assertThat(config(Map.of(), Map.of(), Map.of("FITNESSE_OTEL_LOG_LEVEL", "99999")).logLevel())
                    .isEqualTo(Level.INFO);

            assertThat(captured.warnings()).hasSize(2);
        }
    }

    @Test
    void levelNamesAreCaseInsensitive() {
        assertThat(config(Map.of(), Map.of(), Map.of("FITNESSE_OTEL_LOG_LEVEL", "fine")).logLevel())
                .isEqualTo(Level.FINE);
    }

    @Test
    void sdkDisabledIsCaseInsensitive() {
        assertThat(config(Map.of(), Map.of(), Map.of("OTEL_SDK_DISABLED", "TRUE")).sdkDisabled()).isTrue();
        assertThat(config(Map.of(), Map.of(), Map.of()).sdkDisabled()).isFalse();
    }
}
