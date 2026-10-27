# fitnesse-otel — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `fitnesse-otel`, a Java 17 library (fat jar dropped into `lib/` and referenced by `!path`) that gives FitNesse fixtures a lazily initialized OpenTelemetry SDK. It exports spans, JUL logs and JVM metrics over OTLP HTTP/protobuf to a Collector.

**Architecture:** The `OtelFixtures` facade triggers `OtelBootstrap` once; it builds the SDK through autoconfigure, registers it globally, starts `RuntimeTelemetry`, installs `OtelJulHandler` on the root logger and registers a shutdown hook. `RunIdResource` adds `test.run.id` to the resource through the autoconfigure SPI. Everything is packaged without relocation by maven-shade.

**Tech Stack:** Java 17, Maven, OpenTelemetry Java 1.67.0 (BOM), OpenTelemetry instrumentation 2.33.1-alpha (alpha BOM), JUnit 5, AssertJ, FitNesse 20260313 (tests only).

**Spec:** `docs/superpowers/specs/2026-10-27-fitnesse-otel-design.md`

## Global Constraints

- `maven.compiler.release=17`; coordinates `io.fitnesse:fitnesse-otel:0.1.0-SNAPSHOT`; package `io.fitnesse.otel`.
- BOMs: `io.opentelemetry:opentelemetry-bom:1.67.0`, `io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha:2.33.1-alpha`.
- Bundled dependencies, and only these: `opentelemetry-api`, `opentelemetry-sdk`, `opentelemetry-sdk-extension-autoconfigure`, `opentelemetry-exporter-otlp` (exclusion `opentelemetry-exporter-sender-okhttp`), `opentelemetry-exporter-sender-jdk`, `io.opentelemetry.instrumentation:opentelemetry-runtime-telemetry`. **Deviation from the spec:** the spec names `opentelemetry-runtime-telemetry-java17`, whose last version is 2.27.0-alpha. It was merged into `opentelemetry-runtime-telemetry`, which brings `opentelemetry-instrumentation-api` and `-api-incubator` transitively.
- Banned dependencies (maven-enforcer `bannedDependencies`, build failure): `com.squareup.okhttp3:*`, `org.jetbrains.kotlin:*`, `io.netty:*`, `io.grpc:*`, `com.google.protobuf:*`.
- Fat jar `fitnesse-otel-<v>-all.jar`: shade **without relocation**, `ServicesResourceTransformer`, manifest `Multi-Release: true` (runtime-telemetry is a multi-release JAR), signatures `META-INF/*.SF|*.DSA|*.RSA` excluded. Size < 3 MB.
- Configuration: standard `OTEL_*` variables through autoconfigure. In-house settings: `FITNESSE_OTEL_LOG_LEVEL` (JUL level name, default `INFO`), `FITNESSE_OTEL_SHUTDOWN_TIMEOUT` (seconds, default `5`), `TEST_RUN_ID`. Each has a system-property equivalent (`fitnesse.otel.log.level`, `fitnesse.otel.shutdown.timeout`), with the same precedence as autoconfigure: system property > environment variable > supplied defaults.
- OTel never fails or blocks a fixture: any error leads to a no-op and a **single** WARN, on the JUL logger `io.fitnesse.otel`.
- The JUL handler ignores loggers whose name starts with `io.opentelemetry.`.
- Unit tests: `*Test` (surefire). Integration tests: `*IT` (failsafe, after `package`, with the fat jar available).

## Review Focus

- **Invalid values for `FITNESSE_OTEL_LOG_LEVEL` or `FITNESSE_OTEL_SHUTDOWN_TIMEOUT`** (`"abc"`, `"-3"`, `""`): default value, one WARN, no exception. Tested in task 4 (`ConfigTest`).
- **Degenerate `LogRecord`** (`null` message, `null` logger name, parameters incompatible with the pattern): the handler throws nothing and still exports a log. Tested in task 2.
- **Collector unreachable at shutdown**: the SLIM JVM exits within the expected time, without hanging. Tested in task 5 (`FatJarIT.exitsPromptlyWhenCollectorIsDown`).
- **Concurrent first call from several fixture threads**: a single initialization and a single JUL handler. Tested in task 4.
- **Log emitted outside any span**: no invalid trace_id or span_id is attached, and the log is exported without context. Tested in task 2.

---

## File structure

```
pom.xml
README.md                                                   (task 6)
src/main/java/io/fitnesse/otel/
  OtelJulHandler.java      JUL → OTel logs                  (task 2)
  RunIdResource.java       SPI ResourceProvider test.run.id (task 3)
  Config.java              property/env/default lookup      (task 4)
  OtelRuntime.java         record: instance + shutdown      (task 4)
  OtelBootstrap.java       SDK construction and wiring      (task 4)
  OtelFixtures.java        public facade, lazy init         (task 4)
src/main/resources/META-INF/services/
  io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider   (task 3)
src/test/java/io/fitnesse/otel/
  OtelJulHandlerTest.java, RunIdResourceTest.java, ConfigTest.java, OtelFixturesTest.java
  it/FatJarContentIT.java  fat jar content                  (tasks 1, 3)
  it/FakeCollector.java    fake OTLP collector + /echo      (task 5)
  it/FixtureCompiler.java  compiles the mini fixture        (task 5)
  it/FatJarIT.java         child JVM + fat jar              (task 5)
  it/FitNesseE2EIT.java    FitNesse 20260313 from the command line (task 6)
src/it/fixture/it/fixture/
  SampleFixture.java, SampleMain.java                       (task 5)
```

---

### Task 1: Maven skeleton, enforcer and fat jar

**Files:**
- Create: `pom.xml`, `.gitignore` (`target/`)
- Test: `src/test/java/io/fitnesse/otel/it/FatJarContentIT.java`

**Interfaces:**
- Produces: the artifact `target/fitnesse-otel-0.1.0-SNAPSHOT-all.jar`, and the system property `fatJar` (absolute path of that JAR) passed by failsafe to every `*IT`. The properties `fixtureSrc` (`${project.basedir}/src/it/fixture`) and `fitnesse.version` (`20260313`) are also passed to failsafe from the start.

- [ ] **Step 1: write `pom.xml`**
  - `dependencyManagement`: both BOMs (Global Constraints) and `org.junit:junit-bom:5.13.4`.
  - Compile dependencies: the Global Constraints list. Test dependencies: `junit-jupiter`, `org.assertj:assertj-core:3.27.6`, `io.opentelemetry:opentelemetry-sdk-testing`, `org.fitnesse:fitnesse:20260313`.
  - Plugins:
    - `maven-enforcer-plugin` 3.6.1: `bannedDependencies` with the banned list, `searchTransitive=true`, phase `validate`;
    - `maven-surefire-plugin` and `maven-failsafe-plugin` 3.5.4 (goals `integration-test` and `verify`, system properties above);
    - `maven-shade-plugin` 3.6.1 in phase `package`: `shadedArtifactAttached=true`, `shadedClassifierName=all`, `createDependencyReducedPom=false`, transformers `ServicesResourceTransformer` and `ManifestResourceTransformer` with `<manifestEntries><Multi-Release>true</Multi-Release></manifestEntries>`, filter `*:*` excluding `META-INF/*.SF`, `META-INF/*.DSA`, `META-INF/*.RSA`;
    - test dependencies must not end up in the fat jar: shade only includes the runtime scope, checked by the test below.

- [ ] **Step 2: write `FatJarContentIT`**

```java
class FatJarContentIT {
    static final Path FAT_JAR = Path.of(System.getProperty("fatJar"));

    @Test void containsNoBannedPackages() throws IOException {
        try (var jar = new JarFile(FAT_JAR.toFile())) {
            assertThat(jar.stream().map(JarEntry::getName))
                .noneMatch(n -> n.startsWith("okhttp3/") || n.startsWith("kotlin/")
                        || n.startsWith("io/netty/") || n.startsWith("io/grpc/")
                        || n.startsWith("com/google/protobuf/")
                        || n.startsWith("org/junit/") || n.startsWith("fitnesse/"));
        }
    }
    @Test void mergesServiceFiles() throws IOException {
        // the OTLP HTTP exporter and the JDK sender must be declared
        assertThat(entryText("META-INF/services/io.opentelemetry.exporter.internal.http.HttpSenderProvider"))
            .contains("io.opentelemetry.exporter.sender.jdk.internal.JdkHttpSenderProvider");
        assertThat(entryText("META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider"))
            .contains("OtlpSpanExporterProvider");
    }
    @Test void isMultiRelease() throws IOException {
        try (var jar = new JarFile(FAT_JAR.toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Multi-Release")).isEqualTo("true");
        }
    }
    @Test void staysUnderSizeBudget() throws IOException {
        assertThat(Files.size(FAT_JAR)).isLessThan(3L * 1024 * 1024);
    }
    // entryText(name): reads the entry as UTF-8, fails if it is missing
}
```

If the exact SPI interface names differ in 1.67.0, use the ones actually present in the JARs (`unzip -l`) and keep the intent: the JDK sender is declared, the OTLP span exporter is declared.

- [ ] **Step 3: run** `mvn -q verify`
  Expected: BUILD SUCCESS, the 4 `FatJarContentIT` tests pass. Record the actual fat-jar size (`ls -l target/*-all.jar`) in the commit message. If it exceeds 3 MB, stop and report it rather than silently raising the threshold.

- [ ] **Step 4: check that the enforcer bites.** Temporarily add `com.squareup.okhttp3:okhttp:4.12.0` as a dependency and run `mvn -q validate`. Expected: `bannedDependencies` failure. Then remove the dependency.

- [ ] **Step 5: commit**
```bash
git add pom.xml .gitignore src/test/java/io/fitnesse/otel/it/FatJarContentIT.java
git commit -m "build: Maven skeleton, enforcer and shaded fat jar"
```

---

### Task 2: `OtelJulHandler`

**Files:**
- Create: `src/main/java/io/fitnesse/otel/OtelJulHandler.java`
- Test: `src/test/java/io/fitnesse/otel/OtelJulHandlerTest.java`

**Interfaces:**
- Produces: `final class OtelJulHandler extends java.util.logging.Handler`, with the constructor `OtelJulHandler(io.opentelemetry.api.logs.LoggerProvider provider, java.util.logging.Level minLevel)`, plus `publish(LogRecord)`, `flush()` (no-op) and `close()` (no-op).

Conversion rules:
- The instrumentation scope is the JUL logger name, or `"jul"` if it is null.
- Severity: `SEVERE`→`ERROR`, `WARNING`→`WARN`, `INFO`→`INFO`, `CONFIG` and `FINE`→`DEBUG`, `FINER` and `FINEST`→`TRACE`. The severity text is the JUL level name.
- The body is the message formatted by `java.util.logging.Formatter#formatMessage`. If formatting fails, use the raw message, and if that is null, the empty string.
- Timestamp: `record.getInstant()`. Attribute `thread.id` = `record.getLongThreadID()`.
- Exception: attributes `exception.type`, `exception.message` and `exception.stacktrace`.
- Context: `Context.current()`, which provides automatic correlation.
- Ignored: levels below `minLevel` and loggers prefixed `io.opentelemetry.`.
- A `ThreadLocal<Boolean>` acts as a reentrancy guard. `publish` catches every `Throwable`.

- [ ] **Step 1: write the tests.** Setup: `InMemoryLogRecordExporter` + `SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))`, and an `SdkTracerProvider` to create test spans. These are direct calls to `handler.publish(new LogRecord(...))`, without touching the root logger.

```java
@Test void mapsLevelMessageAndScope()        // INFO "hello {0}" ["bob"] logger "a.b" → severity INFO, body "hello bob", scope "a.b"
@Test void mapsAllLevels()                   // SEVERE→ERROR, WARNING→WARN, CONFIG→DEBUG, FINE→DEBUG, FINEST→TRACE (minLevel=ALL)
@Test void recordsException()                // thrown IllegalStateException("boom") → exception.type="java.lang.IllegalStateException", exception.message="boom", stacktrace contains "boom"
@Test void correlatesWithCurrentSpan()       // inside span.makeCurrent() → log.getSpanContext().getTraceId() == span trace id
@Test void hasNoSpanContextOutsideSpan()     // outside a span → !log.getSpanContext().isValid()
@Test void dropsBelowMinLevel()              // minLevel=INFO, FINE record → 0 logs exported
@Test void ignoresOpenTelemetryLoggers()     // logger "io.opentelemetry.exporter.X" → 0 logs
@Test void survivesDegenerateRecords()       // null message, null logger, pattern "{0" with params → no exception, 3 logs exported, scope "jul" for the null logger
@Test void doesNotRecurse()                  // a fake LoggerProvider whose emit() calls handler.publish(...) → a single emit, no StackOverflowError
@Test void neverThrows()                     // a LoggerProvider whose get() throws RuntimeException → publish throws nothing
```

- [ ] **Step 2: run** `mvn -q test -Dtest=OtelJulHandlerTest`. Expected: compilation failure, the class does not exist yet.
- [ ] **Step 3: implement `OtelJulHandler`** following the rules above. Use a private `SimpleFormatter` instance only for `formatMessage`.
- [ ] **Step 4: run** `mvn -q test -Dtest=OtelJulHandlerTest`. Expected: the 10 tests pass.
- [ ] **Step 5: commit**
```bash
git add src/main/java/io/fitnesse/otel/OtelJulHandler.java src/test/java/io/fitnesse/otel/OtelJulHandlerTest.java
git commit -m "feat: JUL handler to OpenTelemetry logs"
```

---

### Task 3: `RunIdResource`

**Files:**
- Create: `src/main/java/io/fitnesse/otel/RunIdResource.java`, `src/main/resources/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider` (one line: `io.fitnesse.otel.RunIdResource`)
- Test: `src/test/java/io/fitnesse/otel/RunIdResourceTest.java`
- Modify: `src/test/java/io/fitnesse/otel/it/FatJarContentIT.java` (one added test)

**Interfaces:**
- Produces: `public final class RunIdResource implements io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider`, with:
  - a public no-arg constructor (for the SPI), which uses `System::getenv`;
  - a package-private constructor `RunIdResource(java.util.function.Function<String,String> env)`;
  - `Resource createResource(ConfigProperties config)`;
  - the constant `static final AttributeKey<String> TEST_RUN_ID = AttributeKey.stringKey("test.run.id")`.

Value rule: the `TEST_RUN_ID` variable if non-blank, otherwise a UUID generated **once per JVM** (static field). The priority of `OTEL_RESOURCE_ATTRIBUTES` is provided by autoconfigure, which applies those attributes after the `ResourceProvider`s: it is not reimplemented, but it is tested.

- [ ] **Step 1: write the tests**
```java
@Test void usesTestRunIdEnv()        // env TEST_RUN_ID="ci-123" → attribute "ci-123"
@Test void generatesStableUuid()     // empty env, two instances → same value, in UUID format
@Test void blankEnvFallsBackToUuid() // TEST_RUN_ID="  " → UUID
@Test void otelResourceAttributesWins() {
    // AutoConfiguredOpenTelemetrySdk.builder()
    //   .addPropertiesSupplier(() -> Map.of("otel.resource.attributes", "test.run.id=from-otel",
    //        "otel.traces.exporter","none","otel.metrics.exporter","none","otel.logs.exporter","none"))
    //   .build() → the tracer provider resource contains test.run.id="from-otel"
    //   (read via SdkTracerProvider#toString or an in-memory SpanExporter added through addTracerProviderCustomizer)
}
@Test void isDiscoveredBySpi()       // same setup without otel.resource.attributes → test.run.id present (UUID or the machine's TEST_RUN_ID)
```
In `FatJarContentIT`, add `registersRunIdResource()`, where `entryText("META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider")` contains `io.fitnesse.otel.RunIdResource`.

- [ ] **Step 2: run** `mvn -q test -Dtest=RunIdResourceTest`. Expected: compilation failure.
- [ ] **Step 3: implement `RunIdResource` and the service file.**
- [ ] **Step 4: run** `mvn -q verify`. Expected: everything passes, including `registersRunIdResource`, which proves that shade merged rather than overwrote the service files.
- [ ] **Step 5: commit**
```bash
git add src/main/java/io/fitnesse/otel/RunIdResource.java src/main/resources src/test/java/io/fitnesse/otel/RunIdResourceTest.java src/test/java/io/fitnesse/otel/it/FatJarContentIT.java
git commit -m "feat: test.run.id resource attribute"
```

---

### Task 4: `Config`, `OtelBootstrap`, `OtelFixtures`

**Files:**
- Create: `src/main/java/io/fitnesse/otel/Config.java`, `OtelRuntime.java`, `OtelBootstrap.java`, `OtelFixtures.java`
- Test: `src/test/java/io/fitnesse/otel/ConfigTest.java`, `OtelFixturesTest.java`

**Interfaces:**
- Consumes: `OtelJulHandler(LoggerProvider, Level)` (task 2); `RunIdResource` is discovered through the SPI (task 3).
- Produces:
  - `final class Config` (package-private):
    - `Config(Map<String,String> defaults, Function<String,String> sysProps, Function<String,String> env)` and `static Config fromSystem(Map<String,String> defaults)`;
    - `Map<String,String> defaults()` (for `addPropertiesSupplier`);
    - `Optional<String> get(String propertyKey)`: system property, then environment variable (key upper-cased, `.` replaced by `_`), then default value. Blank strings are ignored;
    - `boolean sdkDisabled()` (`otel.sdk.disabled` is `true`, case-insensitive);
    - `Level logLevel()` (`fitnesse.otel.log.level`, default `Level.INFO`);
    - `Duration shutdownTimeout()` (`fitnesse.otel.shutdown.timeout`, in seconds, integer ≥ 0, default 5 s);
    - for an invalid value: the default value, and a WARN on the `io.fitnesse.otel` logger.
  - `record OtelRuntime(OpenTelemetry openTelemetry, boolean enabled, Runnable shutdown)` (package-private). `static OtelRuntime noop()` returns `OpenTelemetry.noop()`, `false` and a no-op `shutdown`.
  - `final class OtelBootstrap` (package-private): `static OtelRuntime start(Config config, boolean registerShutdownHook)`. Never throws.
  - `public final class OtelFixtures` (private constructor):
    - `public static Tracer tracer()` (instrumentation scope `"io.fitnesse.otel"`);
    - `public static OpenTelemetry openTelemetry()`;
    - `public static void inject(BiConsumer<String,String> headerSetter)`;
    - `public static boolean isEnabled()`;
    - for tests, package-private: `static void resetForTest(Map<String,String> defaults)` (calls the current `shutdown`, `GlobalOpenTelemetry.resetForTest()`, resets the state and keeps `defaults` for the next initialization; no hook is registered in test mode).

`OtelBootstrap.start` algorithm:
1. If `config.sdkDisabled()`: one INFO, then `OtelRuntime.noop()`.
2. `AutoConfiguredOpenTelemetrySdk.builder().addPropertiesSupplier(config::defaults).build().getOpenTelemetrySdk()` (the supplied defaults have the lowest priority, as in `Config`).
3. `GlobalOpenTelemetry.set(sdk)`. On `IllegalStateException`: `sdk.shutdown()`, reuse `GlobalOpenTelemetry.get()`, and one WARN ("global instance already set, reusing it").
4. `RuntimeTelemetry.create(otel)` (`io.opentelemetry.instrumentation.runtimetelemetry`).
5. `new OtelJulHandler(otel.getLogsBridge(), config.logLevel())`, added to `Logger.getLogger("")`.
6. The `shutdown`, which must be idempotent, removes the handler, closes `RuntimeTelemetry`, then calls `sdk.shutdown().join(timeout)` if the SDK is owned.
7. If `registerShutdownHook`: `Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "fitnesse-otel-shutdown"))`.
8. Any error (`Throwable`) produces a single WARN with the cause, then `OtelRuntime.noop()`. Partially created resources are closed on a best-effort basis.

`OtelFixtures` keeps a `volatile OtelRuntime`, initialized with double-checked locking. `inject` calls `openTelemetry().getPropagators().getTextMapPropagator().inject(Context.current(), headerSetter, BiConsumer::accept)`.

- [ ] **Step 1: write `ConfigTest`**
```java
@Test void sysPropBeatsEnvBeatsDefault()       // get("fitnesse.otel.log.level"): sysprop "FINE" > env FITNESSE_OTEL_LOG_LEVEL "WARNING" > default "SEVERE"
@Test void blankValuesAreIgnored()             // env "  " → default value used
@Test void parsesLevelAndTimeout()             // "WARNING" → Level.WARNING; "12" → Duration.ofSeconds(12)
@Test void invalidValuesFallBackWithOneWarn()  // level "abc", timeout "-3" and "x" → INFO / 5 s, one WARN per invalid value (captured by a test Handler on "io.fitnesse.otel")
@Test void sdkDisabledIsCaseInsensitive()      // "TRUE" → true; absent → false
```
- [ ] **Step 2: write `OtelFixturesTest`.** An `@AfterEach` calls `OtelFixtures.resetForTest(Map.of())`. Shared defaults, `NONE`: `otel.traces.exporter=none`, `otel.metrics.exporter=none`, `otel.logs.exporter=none`.
```java
@Test void initializesLazilyAndInstallsOneHandler()   // resetForTest(NONE); isEnabled() → true; root logger: exactly 1 OtelJulHandler
@Test void concurrentFirstCallsInitializeOnce()       // 16 threads, CountDownLatch, tracer() at the same time → a single OtelJulHandler, same openTelemetry() for all
@Test void injectWritesTraceparent()                  // inside a span from tracer() → map.get("traceparent") matches "00-<traceId>-<spanId>-01"
@Test void sdkDisabledGivesNoop()                     // NONE + otel.sdk.disabled=true → isEnabled() false, span.getSpanContext().isValid() false, 0 handlers
@Test void brokenConfigGivesNoopAndSingleWarn()       // otel.traces.exporter=doesnotexist → isEnabled() false; 3 calls to tracer() → exactly 1 WARN on "io.fitnesse.otel"
@Test void reusesExistingGlobalWithWarn()             // GlobalOpenTelemetry.set(sdk with InMemorySpanExporter) first; a span via tracer() reaches the in-memory exporter; 1 WARN
@Test void earlyGlobalGetDoesNotBreak()               // GlobalOpenTelemetry.get() first; tracer() throws nothing
@Test void shutdownRemovesHandlerAndIsIdempotent()    // resetForTest called twice → 0 OtelJulHandler on the root, no exception; Logger.getLogger("x").info("after") throws nothing
```
- [ ] **Step 3: run** `mvn -q test -Dtest='ConfigTest,OtelFixturesTest'`. Expected: compilation failure.
- [ ] **Step 4: implement** `Config`, `OtelRuntime`, `OtelBootstrap` and `OtelFixtures` following the interfaces and algorithm above.
- [ ] **Step 5: run** `mvn -q verify`. Expected: all unit tests and `FatJarContentIT` pass.
- [ ] **Step 6: commit**
```bash
git add src/main/java/io/fitnesse/otel src/test/java/io/fitnesse/otel/ConfigTest.java src/test/java/io/fitnesse/otel/OtelFixturesTest.java
git commit -m "feat: lazy SDK bootstrap and OtelFixtures facade"
```

---

### Task 5: fat-jar integration test (child JVM and fake collector)

**Files:**
- Create: `src/it/fixture/it/fixture/SampleFixture.java`, `src/it/fixture/it/fixture/SampleMain.java`
- Create: `src/test/java/io/fitnesse/otel/it/FakeCollector.java`, `FixtureCompiler.java`, `FatJarIT.java`

**Interfaces:**
- Consumes: `OtelFixtures.tracer()` and `OtelFixtures.inject(...)` (task 4); the system properties `fatJar` and `fixtureSrc` (task 1).
- Produces (reused by task 6):
  - `final class FakeCollector implements AutoCloseable`: `static FakeCollector start()` (`com.sun.net.httpserver.HttpServer` on port 0); `int port()`; `String endpoint()` (`http://localhost:<port>`); `List<byte[]> bodies(String path)` for `/v1/traces`, `/v1/logs` and `/v1/metrics`, which respond 200 with an empty body; `List<String> echoTraceparents()` for `/echo`, which records the `traceparent` header and responds 200; `static boolean contains(byte[] haystack, byte[] needle)`.
  - `final class FixtureCompiler`: `static Path compile(Path fatJar)`, which compiles `${fixtureSrc}/**/*.java` with `ToolProvider.getSystemJavaCompiler()` (`-classpath fatJar --release 17`) into a temporary directory and returns it.
  - `it.fixture.SampleFixture`: public no-arg constructor and `public int call(String url)`. The method logs through JUL (logger `it.fixture.SampleFixture`, INFO `"calling " + url`), creates a `CLIENT` span named `"GET /echo"`, injects the context into an `HttpRequest.Builder`, sends the request with `java.net.http.HttpClient`, sets `http.response.status_code` and returns the status. This code serves as the model: it is the one from spec section 3.3.
  - `it.fixture.SampleMain.main(String[] args)`: `new SampleFixture().call(args[0])`, then `main` returns normally.

Content checks without a protobuf dependency: strings in OTLP messages appear as-is, in UTF-8, in the binary bodies, and the trace_id appears as 16 raw bytes.

- [ ] **Step 1: write the mini fixture sources** (`SampleFixture`, `SampleMain`) following the interfaces above.
- [ ] **Step 2: write `FatJarIT`.** The child JVM is started with `ProcessBuilder(<java.home>/bin/java, "-cp", fatJar + File.pathSeparator + fixtureClasses, "it.fixture.SampleMain", collector.endpoint() + "/echo")` and awaited for at most 30 s. Output is redirected to a test log file to ease diagnosis.
```java
@Test void exportsAllSignalsWithContextAndRunId() {
    // env: OTEL_EXPORTER_OTLP_ENDPOINT=collector.endpoint(), OTEL_SERVICE_NAME=fixture-it, TEST_RUN_ID=run-it-42
    // expected: exit code 0
    // echoTraceparents(): 1 element matching ^00-[0-9a-f]{32}-[0-9a-f]{16}-01$
    // bodies("/v1/traces"): at least one body contains the trace_id bytes (hex → 16 bytes), "run-it-42", "test.run.id", "fixture-it", "GET /echo"
    // bodies("/v1/logs"): at least one body contains "calling http://localhost:"
    // bodies("/v1/metrics"): non-empty (final collection at shutdown) and one body contains "jvm."
}
@Test void exitsPromptlyWhenCollectorIsDown() {
    // endpoint pointing to a closed port (ServerSocket opened then closed to get a free port)
    // env: FITNESSE_OTEL_SHUTDOWN_TIMEOUT=2, OTEL_EXPORTER_OTLP_TIMEOUT=1000
    // /echo served by a separate FakeCollector
    // expected: the process exits in less than 15 s, with code 0
}
```
- [ ] **Step 3: run** `mvn -q verify -Dit.test=FatJarIT`. Expected: both tests pass. On a failure on `/v1/metrics` or `/v1/logs`, check the `shutdown` flush (task 4) before touching the test.
- [ ] **Step 4: commit**
```bash
git add src/it src/test/java/io/fitnesse/otel/it
git commit -m "test: fat-jar integration with a fake OTLP collector"
```

---

### Task 6: FitNesse 20260313 end-to-end and README

**Files:**
- Create: `src/test/java/io/fitnesse/otel/it/FitNesseE2EIT.java`, `README.md`

**Interfaces:**
- Consumes: `FakeCollector`, `FixtureCompiler` and `it.fixture.SampleFixture` (task 5); the system property `fatJar`.

Setup:
1. Create a temporary wiki `<tmp>/FitNesseRoot/TestPage.wiki`:
```
---
Test
---
!define TEST_SYSTEM {slim}
!path <fatJar>
!path <fixtureClasses>
!path <each JAR of java.class.path whose path does not contain "/io/opentelemetry/">
|import|
|it.fixture|

!|script|sample fixture|
|check|call|<collector.endpoint()>/echo|200|
```
   The JARs added to `!path` give the SLIM JVM FitNesse's runtime dependencies. OTel JARs are excluded so that only one copy exists, the fat jar's.
2. Start FitNesse as a child JVM: `<java.home>/bin/java -cp <java.class.path> fitnesseMain.FitNesseMain -p <free port> -d <tmp> -r FitNesseRoot -o -c "TestPage?test&format=text"`, with environment `JAVA_HOME=<java.home>`, `OTEL_EXPORTER_OTLP_ENDPOINT=collector.endpoint()`, `OTEL_SERVICE_NAME=fitnesse-e2e` and `TEST_RUN_ID=run-e2e-7`. Timeout: 120 s. FitNesse's exit code is the number of failures.

- [ ] **Step 1: write `FitNesseE2EIT`**
```java
@Test void slimJvmInheritsOtelEnvAndExportsFixtureSpans() {
    // expected: exit code 0 (green page); standard output does not contain "Exception"
    // echoTraceparents(): 1 element with a valid traceparent format
    // bodies("/v1/traces"): at least one body contains "run-e2e-7", "fitnesse-e2e" and the trace_id bytes
}
```
- [ ] **Step 2: run** `mvn -q verify -Dit.test=FitNesseE2EIT`. Expected: the test passes.
  - If FitNesse does not find the page, check its storage format (`TestPage.wiki` or `TestPage/content.txt` with `properties.xml`) on FitNesse 20260313, and adapt the setup, not the assertion.
  - If the SLIM JVM does not see the `OTEL_*` variables, **the spec's central assumption is invalidated**: stop and report it.
- [ ] **Step 3: write `README.md`**, with the following sections:
  - **Installation**: copy `fitnesse-otel-<v>-all.jar` into `lib/`, add `!path lib/fitnesse-otel-*.jar` on the root page.
  - **Configuration**: table of the variables `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`, `TEST_RUN_ID`, `FITNESSE_OTEL_LOG_LEVEL`, `FITNESSE_OTEL_SHUTDOWN_TIMEOUT`, `OTEL_SDK_DISABLED`, `OTEL_METRIC_EXPORT_INTERVAL`, set when FitNesse starts.
  - **Contract for fixtures**: `fitnesse-otel` and `opentelemetry-api` with `provided` scope; never bundle OTel; always go through `OtelFixtures` and never through `GlobalOpenTelemetry.get()`; spans only around outgoing calls (`SpanKind.CLIENT`). Example: a copy of `SampleFixture.call`.
  - **Limitations**: logs emitted before the first call to `OtelFixtures` are lost; a level below `INFO` also requires lowering the JUL level of the relevant loggers; `kill -9` loses the most recent data; `opentelemetry-runtime-telemetry` is alpha.
- [ ] **Step 4: run** `mvn -q clean verify`. Expected: BUILD SUCCESS, all unit and integration tests pass.
- [ ] **Step 5: commit**
```bash
git add src/test/java/io/fitnesse/otel/it/FitNesseE2EIT.java README.md
git commit -m "test: FitNesse 20260313 end-to-end; docs: integration README"
```
