# fitnesse-otel

[![Maven Central](https://img.shields.io/maven-central/v/io.github.tortaje/fitnesse-otel.svg)](https://central.sonatype.com/artifact/io.github.tortaje/fitnesse-otel)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

Lightweight OpenTelemetry foundation for FitNesse fixtures. Fixtures trace their external calls (REST, Vault,
S3…), propagate the W3C context (`traceparent`) to the application under test, and export their JUL logs and the
JVM metrics to an **OpenTelemetry Collector** over **OTLP HTTP/protobuf**.

- A single JAR, with no gRPC, Netty, OkHttp or Kotlin: export goes through the JDK HTTP client.
- Java 17 or later, tested with FitNesse 20260313.
- FitNesse itself is not traced: only fixtures produce spans.

## Artifacts

`mvn verify` produces two JARs in `target/`:

| File | Use |
|---|---|
| `fitnesse-otel-<version>.jar` | **Compile fixtures** against it (`provided` scope). Contains only `OtelFixtures` and its internals. |
| `fitnesse-otel-<version>-standalone.jar` | **Deploy it in FitNesse.** Self-contained: bundles OpenTelemetry and all its dependencies. |

## Installation

1. Build: `mvn verify`.
2. Copy `fitnesse-otel-<version>-standalone.jar` into the `lib/` directory of the FitNesse instance.
3. On the wiki root page:

```
!path lib/fitnesse-otel-*-standalone.jar
!path lib/fixtures-*.jar
```

The SDK starts inside the SLIM JVM (the one FitNesse launches to run fixtures) on the **first call** a fixture makes
to `OtelFixtures`. No other FitNesse configuration is needed.

## Configuration

Each setting can come from three places, the first one found wins:

1. a **system property** of the SLIM JVM (`-Dotel.service.name=...`);
2. an **environment variable** set when starting FitNesse, which the SLIM JVM inherits (`OTEL_SERVICE_NAME=...`);
3. a **`fitnesse-otel.properties` file** on the fixtures' classpath.

### Configuration file

The most convenient option: keep the settings next to the wiki, in a directory added to the classpath with `!path`.

```properties
# conf/fitnesse-otel.properties
otel.service.name=fitnesse-fixtures
otel.exporter.otlp.endpoint=http://collector:4318
fitnesse.otel.log.level=INFO
```

```
!path lib/fitnesse-otel-*-standalone.jar
!path lib/fixtures-*.jar
!path conf
```

- Keys are the property form of the variables below: lower case, `_` replaced by `.`
  (`OTEL_EXPORTER_OTLP_ENDPOINT` → `otel.exporter.otlp.endpoint`).
- The file has the lowest priority, so CI can still override any value with an environment variable.
- A missing file is ignored; an unreadable one logs a WARN and is ignored. The loaded file is logged at INFO.
- `TEST_RUN_ID` is read from the environment only; in the file, use `otel.resource.attributes=test.run.id=...`.
- Different suites can point to different `conf` directories.

### Settings

| Variable | Purpose | Default |
|---|---|---|
| `OTEL_SERVICE_NAME` | Service name in the telemetry | `unknown_service:java` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Collector URL | `http://localhost:4318` |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | OTLP protocol (only `http/protobuf` is bundled) | `http/protobuf` |
| `TEST_RUN_ID` | Run identifier, published as the `test.run.id` resource attribute | UUID generated per JVM |
| `FITNESSE_OTEL_LOG_LEVEL` | Minimum JUL level exported (a JUL level name) | `INFO` |
| `FITNESSE_OTEL_SHUTDOWN_TIMEOUT` | Maximum time (seconds) for the flush when the JVM stops | `5` |
| `OTEL_SDK_DISABLED` | `true` to turn everything off (no-op) | `false` |
| `OTEL_METRIC_EXPORT_INTERVAL` | Metric export interval (ms) | `60000` |

Every other standard `OTEL_*` variable applies (resource attributes, headers, export queues…).
`OTEL_RESOURCE_ATTRIBUTES=test.run.id=...` takes precedence over `TEST_RUN_ID`.

## Contract for fixtures

- Declare `io.github.tortaje:fitnesse-otel` and `io.opentelemetry:opentelemetry-api` with **`provided`** scope.
  **Never bundle** OpenTelemetry in the fixture JAR: only one copy may exist on the classpath.
- Always go through `OtelFixtures`, **never** through `GlobalOpenTelemetry.get()`. An early call to
  `GlobalOpenTelemetry.get()` can freeze the global instance as a no-op.
- Create spans **only** around outgoing calls (`SpanKind.CLIENT`).

```java
public int call(String url) throws Exception {
    Span span = OtelFixtures.tracer().spanBuilder("GET /echo").setSpanKind(SpanKind.CLIENT).startSpan();
    try (Scope ignored = span.makeCurrent()) {
        LOG.info("calling " + url);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        OtelFixtures.inject(request::header);   // adds traceparent
        HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        span.setAttribute("http.response.status_code", response.statusCode());
        if (response.statusCode() >= 500) {
            span.setStatus(StatusCode.ERROR);
        }
        return response.statusCode();
    } catch (Exception e) {
        span.recordException(e);
        span.setStatus(StatusCode.ERROR);
        throw e;
    } finally {
        span.end();
    }
}
```

`OtelFixtures.inject` accepts any `BiConsumer<String, String>`, so it works with any HTTP client.

## Behavior when things go wrong

OpenTelemetry never fails or slows down a test:

- an initialization error logs a single WARN on the `io.github.tortaje.otel` logger, then falls back to a no-op;
- if the collector is slow or unreachable, data goes through in-memory queues and is dropped when a queue is full;
- when the JVM stops, the flush is bounded by `FITNESSE_OTEL_SHUTDOWN_TIMEOUT`.

## Limitations

- Logs emitted **before** the first call to `OtelFixtures` are not exported.
- A `FITNESSE_OTEL_LOG_LEVEL` below `INFO` also requires lowering the JUL level of the relevant loggers
  (for example in `logging.properties`).
- A `kill -9` of the SLIM JVM loses the most recent data.
- JVM metrics come from `opentelemetry-runtime-telemetry`, and host/process/OS/runtime resource attributes from
  `opentelemetry-resources`; both are still **alpha**, so names may change between versions.
- Each external call is its own trace: group the telemetry of one run by `test.run.id`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
