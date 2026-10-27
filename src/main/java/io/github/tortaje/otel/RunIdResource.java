package io.github.tortaje.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.resources.Resource;
import java.util.UUID;
import java.util.function.Function;

/**
 * Adds {@code test.run.id} to the resource: the {@code TEST_RUN_ID} variable, otherwise a UUID generated once per JVM.
 * A value given in {@code OTEL_RESOURCE_ATTRIBUTES} wins, because autoconfigure applies it after the providers.
 */
public final class RunIdResource implements ResourceProvider {

    static final AttributeKey<String> TEST_RUN_ID = AttributeKey.stringKey("test.run.id");

    private static final String GENERATED_RUN_ID = UUID.randomUUID().toString();

    private final Function<String, String> env;

    /** Constructor used by the autoconfigure SPI; reads {@code TEST_RUN_ID} from the environment. */
    public RunIdResource() {
        this(System::getenv);
    }

    RunIdResource(Function<String, String> env) {
        this.env = env;
    }

    @Override
    public Resource createResource(ConfigProperties config) {
        String fromEnv = env.apply("TEST_RUN_ID");
        String runId = fromEnv == null || fromEnv.isBlank() ? GENERATED_RUN_ID : fromEnv.trim();
        return Resource.create(Attributes.of(TEST_RUN_ID, runId));
    }
}
