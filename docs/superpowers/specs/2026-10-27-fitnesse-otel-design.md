# Design: `fitnesse-otel` library (lightweight OpenTelemetry for FitNesse fixtures)

- **Date:** 2026-10-27
- **Status:** design agreed in discussion, pending review
- **Supersedes:** `initial-spec` (the FitNesse plugin described in the initial spec is dropped, see §2)

## 1. Goal

Give in-house FitNesse fixtures (REST, Vault, S3/COS…) a **lightweight OpenTelemetry foundation** that lets them:

- create **spans only around their external calls** and propagate the W3C context (`traceparent`) to the application under test;
- **export logs** (JUL) correlated with traces;
- export the **runtime metrics of the JVM** running the fixtures;

all to an **OpenTelemetry Collector** over **OTLP HTTP/protobuf (port 4318)**, with a **minimal number of dependencies**.

### Out of scope

- Tracing FitNesse internals (pages, suites, SLIM tables, Jetty).
- Logs and metrics of the FitNesse (wiki) JVM.
- Sending directly to an APM (Dynatrace or other): only the Collector is targeted, and everything APM-related is configured on the Collector side.
- Capturing `System.out` / `System.err`.
- A helper tied to a particular HTTP client: the library is independent of the HTTP client.

## 2. Context and key decisions

| Decision | Rationale |
|---|---|
| **A library, not a FitNesse plugin** | By default, FitNesse runs fixtures in a **separate SLIM JVM**. An SDK initialized in the wiki JVM would be invisible to fixtures. The only in-process mode (`?test&debug`) cannot be driven by configuration. So only the SLIM JVM is instrumented. |
| **Single fat jar `fitnesse-otel.jar` in `lib/`, referenced by `!path`** | Several fixture JARs share the classpath. Bundling OTel in each would cause duplicate classes, several `GlobalOpenTelemetry` initializations and duplicate JUL handlers. |
| **No relocation** | The JAR only lives in the SLIM JVM, with no contact with Jetty or FitNesse. Fixtures use the standard OTel API. |
| **OTLP HTTP/protobuf + JDK sender (`java.net.http`)** | No gRPC/Netty, no OkHttp/Kotlin. Java 17 minimum. |
| **`opentelemetry-sdk-extension-autoconfigure`** | Full support for the standard `OTEL_*` variables without reimplementing them. Bundled in the fat jar, with no extra transitive dependency. |
| **Lazy initialization** | Initialization on a fixture's first call to `OtelFixtures`. No FitNesse configuration beyond `!path`. Logs emitted before that first call are not exported (accepted). |
| **Correlation through a `test.run.id` resource attribute** | No common trace root (FitNesse is not traced): each external call is its own trace. `test.run.id` groups all the telemetry of one run. |

Targets: **Java 17+**, **FitNesse 20260313**.

## 3. Architecture

### 3.1 FitNesse integration

Wiki root page:

```
!path lib/fitnesse-otel-*.jar
!path lib/fixtures-*.jar
```

Configuration is done through environment variables set when FitNesse starts. The SLIM JVM, launched by FitNesse, inherits them:

```
OTEL_SERVICE_NAME=fitnesse-fixtures
OTEL_EXPORTER_OTLP_ENDPOINT=http://<collector>:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
TEST_RUN_ID=<id provided by CI>          # optional
```

### 3.2 Components (package `io.fitnesse.otel`)

| Class | Visibility | Role |
|---|---|---|
| `OtelFixtures` | **public** | Facade for fixtures: `Tracer tracer()`, `void inject(BiConsumer<String,String> headerSetter)`, `boolean isEnabled()`. On the first call, triggers `OtelBootstrap` (once, thread-safe). |
| `OtelBootstrap` | internal | Builds the SDK through autoconfigure, registers it in `GlobalOpenTelemetry`, starts JVM runtime metrics, installs `OtelJulHandler` on the root logger and registers the shutdown hook. |
| `OtelJulHandler` | internal | `java.util.logging.Handler` that converts each `LogRecord` into an OTel log: severity, formatted message, logger name, exception (`exception.*`), and current trace context. |
| `RunIdResource` | internal (SPI) | Autoconfigure `ResourceProvider` that adds `test.run.id`. Priority: value already present in `OTEL_RESOURCE_ATTRIBUTES` > `TEST_RUN_ID` variable > generated UUID. |

`inject` uses the configured propagator (W3C Trace Context by default) with the current context.

### 3.3 Usage contract for fixtures

- Depend on `fitnesse-otel` (thin JAR, no dependencies) and `opentelemetry-api` with **`provided`** scope. **Never bundle** OTel in the fixture JAR.
- **Always go through `OtelFixtures`** and never call `GlobalOpenTelemetry.get()` directly. A `get()` before initialization freezes the global instance as a no-op.
- Create spans **only** around outgoing calls (`SpanKind.CLIENT`), following the OTel semantic conventions (`http.request.method`, `url.full`, `server.address`, `http.response.status_code`…).

Example (REST fixture, code in the fixture JAR):

```java
Span span = OtelFixtures.tracer().spanBuilder("GET /orders")
        .setSpanKind(SpanKind.CLIENT).startSpan();
try (Scope s = span.makeCurrent()) {
    OtelFixtures.inject(requestBuilder::header);   // adds traceparent
    HttpResponse<String> resp = client.send(requestBuilder.build(), ofString());
    span.setAttribute("http.response.status_code", resp.statusCode());
    if (resp.statusCode() >= 500) span.setStatus(StatusCode.ERROR);
    return resp;
} catch (Exception e) {
    span.recordException(e);
    span.setStatus(StatusCode.ERROR);
    throw e;
} finally {
    span.end();
}
```

## 4. Signals

- **Traces:** only the spans created by fixtures. One trace per external call, continued on the application side thanks to `traceparent`.
- **Logs:** everything that goes through JUL in the SLIM JVM after initialization. `io.opentelemetry.*` loggers are excluded. The minimum exported level is `INFO` by default, configurable through `FITNESSE_OTEL_LOG_LEVEL` (a JUL level name). A fixture using SLF4J adds the `slf4j-jdk14` binding on its side.
- **Metrics:** JVM runtime (heap, GC, threads, CPU, classes) through `opentelemetry-runtime-telemetry`. Cumulative temporality by default (standard). Default interval of 60 s, configurable through `OTEL_METRIC_EXPORT_INTERVAL`. A short-lived SLIM JVM may send only one collection, at shutdown.
- **Resource:** `service.name` (through `OTEL_SERVICE_NAME`), `test.run.id`, plus the SDK's standard attributes (process, host, OS, runtime).

## 5. Lifecycle and error handling

**Principle:** OTel never fails or slows down a test. When in doubt, the library degrades to a no-op and says so.

1. **Initialization**
   - Guarded by a `catch (Throwable)`. On failure, a single JUL WARN with the cause, then a no-op for the rest of the JVM's life.
   - `OTEL_SDK_DISABLED=true` gives an intentional no-op, with an INFO message.
   - If a `GlobalOpenTelemetry` instance already exists, it is reused with a WARN, without an exception. The JUL handler and metrics are still installed if possible.
   - Single initialization, safe under concurrency.
2. **Runtime**
   - Batch processors for all three signals: fixture threads only append to a queue.
   - Queue full: data is dropped, without blocking.
   - Transient export errors are retried by the exporter (built-in backoff).
   - Export errors are logged by the SDK (rate-limited) under `io.opentelemetry.*`: visible on the console, never exported.
   - Tuning through the standard `OTEL_BSP_*`, `OTEL_BLRP_*` and `OTEL_EXPORTER_OTLP_TIMEOUT` variables.
3. **JUL handler**
   - Per-thread reentrancy guard to prevent any recursion.
   - `publish()` never propagates an exception.
4. **Shutdown** (shutdown hook)
   1. Removes `OtelJulHandler` from the root logger.
   2. Calls `sdk.shutdown()`, which flushes spans, logs and metrics (including a final collection).
   3. Waits at most `FITNESSE_OTEL_SHUTDOWN_TIMEOUT` (in seconds, 5 by default), then gives up.

   Covers SLIM `bye`, `System.exit` and SIGTERM. A `kill -9` loses the most recent data (accepted).

## 6. Build and packaging

- **Maven**, a single module, `maven.compiler.release=17`.
- **BOMs:** `io.opentelemetry:opentelemetry-bom` and `io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha`.
- **Dependencies (compile scope, bundled):**
  - `io.opentelemetry:opentelemetry-api`
  - `io.opentelemetry:opentelemetry-sdk`
  - `io.opentelemetry:opentelemetry-sdk-extension-autoconfigure`
  - `io.opentelemetry:opentelemetry-exporter-otlp` (**exclusion**: `opentelemetry-exporter-sender-okhttp`)
  - `io.opentelemetry:opentelemetry-exporter-sender-jdk`
  - `io.opentelemetry.instrumentation:opentelemetry-runtime-telemetry` (formerly `runtime-telemetry-java17`, merged after 2.27.0; **alpha**: API and metric names may change, internal use only)
  - `io.opentelemetry.instrumentation:opentelemetry-resources` (**alpha**: host, process, OS and runtime resource providers, discovered by autoconfigure)
- **Artifacts produced:**
  - `fitnesse-otel-<v>.jar`: thin JAR that fixtures link against with `provided` scope;
  - `fitnesse-otel-<v>-all.jar`: fat jar built with `maven-shade-plugin`, **without relocation**, with `ServicesResourceTransformer` (merges `META-INF/services`, required for autoconfigure). This is the file deployed in `lib/`.
- **`maven-enforcer-plugin`** (`bannedDependencies`) fails the build if any of these artifacts appears: `com.squareup.okhttp3:*`, `org.jetbrains.kotlin:*`, `io.netty:*`, `io.grpc:*`, `com.google.protobuf:*`.

## 7. Test strategy

1. **Unit tests** (JUnit 5 and `opentelemetry-sdk-testing` with test scope, using in-memory exporters)
   - `OtelJulHandler`: level mapping, message formatted with its parameters, exception, `trace_id`/`span_id` correlation, exclusion of `io.opentelemetry.*`, no recursion, level filtering, no exception propagated.
   - `RunIdResource`: the three sources and their priority.
   - `OtelFixtures` / `OtelBootstrap`: no-op and a single WARN on initialization failure, `OTEL_SDK_DISABLED`, single initialization under concurrency, reuse of an existing global instance, `inject` producing a valid `traceparent`.
2. **Fat-jar integration test**
   - Fake OTLP collector based on `com.sun.net.httpserver` (free port) that records the requests received on `/v1/traces`, `/v1/logs` and `/v1/metrics`.
   - Starts a child JVM with the fat jar and a mini fixture: one client span, one JUL log, and one HTTP call to the fake server.
   - Checks after the JVM exits: all three signals received (which validates the shutdown flush, service-file merging and the JDK sender), `traceparent` present and consistent with the exported span, `test.run.id` in the resource.
3. **FitNesse 20260313 end-to-end test** (FitNesse as a test dependency)
   - Test mini wiki with `!path` to the fat jar and the mini fixture; FitNesse started from the command line with `-c "TestPage?test&format=text"` and `OTEL_EXPORTER_OTLP_ENDPOINT` pointing at the fake collector.
   - Validates the central assumption: the SLIM JVM inherits `OTEL_*` and loads the library from `!path`.
4. **Packaging check**
   - No `okhttp3.*`, `kotlin.*`, `io.netty.*` or `io.grpc.*` class in the fat jar.
   - The `META-INF/services` files required by autoconfigure and the exporter are present.
   - The fat jar stays under a size threshold, initially 3 MB, to be adjusted after the first measurement.

## 8. Known risks

| Risk | Mitigation |
|---|---|
| A fixture calls `GlobalOpenTelemetry.get()` before initialization, freezing the instance as a no-op | Contract rule (§3.3), WARN and reuse at bootstrap. |
| A fixture bundles its own copy of OTel | Contract rule (`provided`) and documentation. |
| `opentelemetry-runtime-telemetry` is alpha | Version pinned by the BOM, internal use only, to be reviewed at each upgrade. |
| Logs emitted before the first call to `OtelFixtures` | Accepted limitation of lazy initialization. |
| SLIM JVM killed abruptly | Loss of the most recent data accepted. |
