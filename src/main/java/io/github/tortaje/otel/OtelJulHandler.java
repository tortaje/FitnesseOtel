package io.github.tortaje.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * JUL handler that converts each {@link LogRecord} into an OpenTelemetry log, correlated with the current trace context.
 * {@code io.opentelemetry.*} loggers are ignored so that an export error never exports itself.
 */
final class OtelJulHandler extends Handler {

    private static final String IGNORED_PREFIX = "io.opentelemetry.";
    private static final String DEFAULT_SCOPE = "jul";

    private static final AttributeKey<Long> THREAD_ID = AttributeKey.longKey("thread.id");
    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    private final LoggerProvider provider;
    private final Formatter formatter = new SimpleFormatter();
    private final ThreadLocal<Boolean> publishing = ThreadLocal.withInitial(() -> Boolean.FALSE);

    OtelJulHandler(LoggerProvider provider, Level minLevel) {
        this.provider = provider;
        setLevel(minLevel);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record) || isIgnored(record.getLoggerName()) || publishing.get()) {
            return;
        }
        publishing.set(Boolean.TRUE);
        try {
            emit(record);
        } catch (Throwable ignored) {
            // an OTel problem must never break the fixture's logging call
        } finally {
            publishing.set(Boolean.FALSE);
        }
    }

    private void emit(LogRecord record) {
        String scope = record.getLoggerName() == null ? DEFAULT_SCOPE : record.getLoggerName();
        LogRecordBuilder builder = provider.get(scope).logRecordBuilder()
                .setTimestamp(record.getInstant())
                .setSeverity(severity(record.getLevel()))
                .setSeverityText(record.getLevel().getName())
                .setBody(message(record))
                .setAttribute(THREAD_ID, record.getLongThreadID())
                .setContext(Context.current());
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            builder.setAttribute(EXCEPTION_TYPE, thrown.getClass().getName());
            if (thrown.getMessage() != null) {
                builder.setAttribute(EXCEPTION_MESSAGE, thrown.getMessage());
            }
            builder.setAttribute(EXCEPTION_STACKTRACE, stackTrace(thrown));
        }
        builder.emit();
    }

    private static boolean isIgnored(String loggerName) {
        return loggerName != null && loggerName.startsWith(IGNORED_PREFIX);
    }

    private String message(LogRecord record) {
        String formatted;
        try {
            formatted = formatter.formatMessage(record);
        } catch (RuntimeException e) {
            formatted = record.getMessage();
        }
        return formatted == null ? "" : formatted;
    }

    static Severity severity(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return Severity.ERROR;
        }
        if (value >= Level.WARNING.intValue()) {
            return Severity.WARN;
        }
        if (value >= Level.INFO.intValue()) {
            return Severity.INFO;
        }
        if (value >= Level.FINE.intValue()) {
            return Severity.DEBUG;
        }
        return Severity.TRACE;
    }

    private static String stackTrace(Throwable thrown) {
        StringWriter out = new StringWriter();
        thrown.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
