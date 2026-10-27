package io.github.tortaje.otel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Captures the WARNs emitted on the {@code io.github.tortaje.otel} logger during a test. */
final class CapturedWarnings extends Handler implements AutoCloseable {

    private final Logger logger = Logger.getLogger("io.github.tortaje.otel");
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    CapturedWarnings() {
        setLevel(Level.ALL);
        logger.addHandler(this);
    }

    List<String> warnings() {
        return records.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .map(LogRecord::getMessage)
                .toList();
    }

    @Override
    public void publish(LogRecord record) {
        records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        logger.removeHandler(this);
    }
}
