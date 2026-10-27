package io.github.tortaje.otel;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads configuration with the same precedence as OTel autoconfigure:
 * system property, then environment variable, then the supplied defaults, then the optional
 * {@value #CONFIG_FILE} found on the classpath (for instance a directory added with {@code !path}).
 */
final class Config {

    static final String LOG_LEVEL = "fitnesse.otel.log.level";
    static final String SHUTDOWN_TIMEOUT = "fitnesse.otel.shutdown.timeout";
    static final String SDK_DISABLED = "otel.sdk.disabled";
    static final String CONFIG_FILE = "fitnesse-otel.properties";

    private static final Logger LOG = Logger.getLogger("io.github.tortaje.otel");
    private static final Level DEFAULT_LOG_LEVEL = Level.INFO;
    private static final Map<String, Level> LEVELS_BY_NAME = Stream.of(Level.OFF, Level.SEVERE, Level.WARNING,
                    Level.INFO, Level.CONFIG, Level.FINE, Level.FINER, Level.FINEST, Level.ALL)
            .collect(Collectors.toUnmodifiableMap(Level::getName, l -> l));
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final Map<String, String> defaults;
    private final Function<String, String> sysProps;
    private final Function<String, String> env;

    Config(Map<String, String> defaults, Function<String, String> sysProps, Function<String, String> env) {
        this.defaults = Map.copyOf(defaults);
        this.sysProps = sysProps;
        this.env = env;
    }

    static Config fromSystem(Map<String, String> defaults) {
        return create(defaults, Config.class.getClassLoader(), System::getProperty, System::getenv);
    }

    /** Builds a config whose defaults are the supplied ones, layered over the classpath {@value #CONFIG_FILE}. */
    static Config create(Map<String, String> defaults, ClassLoader loader, Function<String, String> sysProps,
            Function<String, String> env) {
        Map<String, String> merged = new HashMap<>(loadConfigFile(loader));
        merged.putAll(defaults);
        return new Config(merged, sysProps, env);
    }

    private static Map<String, String> loadConfigFile(ClassLoader loader) {
        URL url = loader.getResource(CONFIG_FILE);
        if (url == null) {
            return Map.of();
        }
        Properties properties = new Properties();
        try (InputStream in = url.openStream()) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "fitnesse-otel: cannot read " + url + ", ignoring it", e);
            return Map.of();
        }
        LOG.info("fitnesse-otel: configuration loaded from " + url);
        Map<String, String> values = new HashMap<>();
        properties.stringPropertyNames().forEach(name -> values.put(name, properties.getProperty(name).trim()));
        return values;
    }

    Map<String, String> defaults() {
        return defaults;
    }

    Optional<String> get(String propertyKey) {
        String envKey = propertyKey.toUpperCase(Locale.ROOT).replace('.', '_');
        return nonBlank(sysProps.apply(propertyKey))
                .or(() -> nonBlank(env.apply(envKey)))
                .or(() -> nonBlank(defaults.get(propertyKey)));
    }

    boolean sdkDisabled() {
        return get(SDK_DISABLED).map(v -> v.equalsIgnoreCase("true")).orElse(false);
    }

    Level logLevel() {
        Optional<String> value = get(LOG_LEVEL);
        if (value.isEmpty()) {
            return DEFAULT_LOG_LEVEL;
        }
        // only level names are accepted: Level.parse would also take any integer
        Level level = LEVELS_BY_NAME.get(value.get().toUpperCase(Locale.ROOT));
        return level != null ? level : invalid(LOG_LEVEL, value.get(), DEFAULT_LOG_LEVEL);
    }

    Duration shutdownTimeout() {
        Optional<String> value = get(SHUTDOWN_TIMEOUT);
        if (value.isEmpty()) {
            return DEFAULT_SHUTDOWN_TIMEOUT;
        }
        try {
            long seconds = Long.parseLong(value.get());
            if (seconds >= 0) {
                return Duration.ofSeconds(seconds);
            }
        } catch (NumberFormatException ignored) {
            // handled as an invalid value below
        }
        return invalid(SHUTDOWN_TIMEOUT, value.get(), DEFAULT_SHUTDOWN_TIMEOUT);
    }

    private static <T> T invalid(String key, String value, T fallback) {
        LOG.warning("fitnesse-otel: invalid value '" + value + "' for " + key + ", using " + fallback);
        return fallback;
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }
}
