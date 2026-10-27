package io.github.tortaje.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtelJulHandlerTest {

    private InMemoryLogRecordExporter exporter;
    private SdkLoggerProvider loggerProvider;

    @BeforeEach
    void setUp() {
        exporter = InMemoryLogRecordExporter.create();
        loggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build();
    }

    private static LogRecord record(Level level, String logger, String message, Object... params) {
        LogRecord r = new LogRecord(level, message);
        r.setLoggerName(logger);
        if (params.length > 0) {
            r.setParameters(params);
        }
        return r;
    }

    private List<LogRecordData> exported() {
        return exporter.getFinishedLogRecordItems();
    }

    @Test
    void mapsLevelMessageAndScope() {
        new OtelJulHandler(loggerProvider, Level.INFO).publish(record(Level.INFO, "a.b", "hello {0}", "bob"));

        assertThat(exported()).hasSize(1);
        LogRecordData log = exported().get(0);
        assertThat(log.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(log.getSeverityText()).isEqualTo("INFO");
        assertThat(log.getBodyValue().asString()).isEqualTo("hello bob");
        assertThat(log.getInstrumentationScopeInfo().getName()).isEqualTo("a.b");
    }

    @Test
    void mapsAllLevels() {
        OtelJulHandler handler = new OtelJulHandler(loggerProvider, Level.ALL);
        for (Level level : List.of(Level.SEVERE, Level.WARNING, Level.CONFIG, Level.FINE, Level.FINEST)) {
            handler.publish(record(level, "a", "m"));
        }

        assertThat(exported()).extracting(LogRecordData::getSeverity)
                .containsExactly(Severity.ERROR, Severity.WARN, Severity.DEBUG, Severity.DEBUG, Severity.TRACE);
    }

    @Test
    void recordsException() {
        LogRecord r = record(Level.SEVERE, "a", "failed");
        r.setThrown(new IllegalStateException("boom"));

        new OtelJulHandler(loggerProvider, Level.INFO).publish(r);

        var attributes = exported().get(0).getAttributes();
        assertThat(attributes.get(AttributeKey.stringKey("exception.type"))).isEqualTo("java.lang.IllegalStateException");
        assertThat(attributes.get(AttributeKey.stringKey("exception.message"))).isEqualTo("boom");
        assertThat(attributes.get(AttributeKey.stringKey("exception.stacktrace"))).contains("boom");
    }

    @Test
    void correlatesWithCurrentSpan() {
        // an SDK tracer without an active processor returns no-op spans: one is needed to get a valid context
        Span span = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
                .build().get("test").spanBuilder("s").startSpan();
        assertThat(span.getSpanContext().isValid()).isTrue();
        try (Scope ignored = span.makeCurrent()) {
            new OtelJulHandler(loggerProvider, Level.INFO).publish(record(Level.INFO, "a", "in span"));
        } finally {
            span.end();
        }

        assertThat(exported().get(0).getSpanContext().getTraceId()).isEqualTo(span.getSpanContext().getTraceId());
        assertThat(exported().get(0).getSpanContext().getSpanId()).isEqualTo(span.getSpanContext().getSpanId());
    }

    @Test
    void hasNoSpanContextOutsideSpan() {
        new OtelJulHandler(loggerProvider, Level.INFO).publish(record(Level.INFO, "a", "no span"));

        assertThat(exported()).hasSize(1);
        assertThat(exported().get(0).getSpanContext().isValid()).isFalse();
    }

    @Test
    void dropsBelowMinLevel() {
        new OtelJulHandler(loggerProvider, Level.INFO).publish(record(Level.FINE, "a", "too verbose"));

        assertThat(exported()).isEmpty();
    }

    @Test
    void ignoresOpenTelemetryLoggers() {
        new OtelJulHandler(loggerProvider, Level.INFO)
                .publish(record(Level.WARNING, "io.opentelemetry.exporter.X", "export failed"));

        assertThat(exported()).isEmpty();
    }

    @Test
    void survivesDegenerateRecords() {
        OtelJulHandler handler = new OtelJulHandler(loggerProvider, Level.INFO);

        assertThatCode(() -> {
            handler.publish(record(Level.INFO, "a", null));
            handler.publish(record(Level.INFO, null, "no logger"));
            handler.publish(record(Level.INFO, "a", "broken {0", "x"));
        }).doesNotThrowAnyException();

        assertThat(exported()).hasSize(3);
        assertThat(exported().get(1).getInstrumentationScopeInfo().getName()).isEqualTo("jul");
    }

    @Test
    void doesNotRecurse() {
        AtomicReference<OtelJulHandler> handlerRef = new AtomicReference<>();
        LogRecordProcessor reentrant = new LogRecordProcessor() {
            @Override
            public void onEmit(Context context, ReadWriteLogRecord logRecord) {
                handlerRef.get().publish(record(Level.INFO, "a", "logged while emitting"));
            }
        };
        SdkLoggerProvider provider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(reentrant)
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build();
        handlerRef.set(new OtelJulHandler(provider, Level.INFO));

        handlerRef.get().publish(record(Level.INFO, "a", "outer"));

        assertThat(exported()).extracting(l -> l.getBodyValue().asString()).containsExactly("outer");
    }

    @Test
    void neverThrows() {
        LoggerProvider failing = new LoggerProvider() {
            @Override
            public LoggerBuilder loggerBuilder(String instrumentationScopeName) {
                throw new RuntimeException("provider broken");
            }

            @Override
            public Logger get(String instrumentationScopeName) {
                throw new RuntimeException("provider broken");
            }
        };

        assertThatCode(() -> new OtelJulHandler(failing, Level.INFO).publish(record(Level.INFO, "a", "m")))
                .doesNotThrowAnyException();
    }
}
