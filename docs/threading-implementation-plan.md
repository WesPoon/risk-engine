# Threading Implementation Plan – ExecutorService / Thread Pool

## Overview

Four parallelism candidates were identified in the `recalculate()` pipeline inside `RiskEngine`.
They are listed below in recommended implementation order.

---

## Candidate 1 — Parallel Per-Trade Pricing (Priority: High)

### Why
`BlackScholesPricer.price()` runs a 50-iteration Newton-Raphson implied-vol loop per trade.
It is the dominant CPU cost in the pipeline. Each call is fully independent —
`BlackScholesPricer` is stateless (pure math, no instance fields).

### Location
`RiskEngine.recalculate()` — pricing loop
```java
// BEFORE
for (Trade t : trades) {
    RiskAttributes ra = pricer.price(t);
    attributes.put(t.getTradeId(), ra);
}
```

### Changes Required
| File | Change |
|---|---|
| `RiskEngine.java` | Inject a `ExecutorService` (fixed thread pool, size = `Runtime.getRuntime().availableProcessors()`). Submit one `Callable` per trade. Collect `Future<Map.Entry<String,RiskAttributes>>` results. |
| `RiskEngine.java` | Replace `LinkedHashMap` result map with `ConcurrentHashMap` (or collect from futures after `invokeAll`). |

### Implementation Sketch
```java
// RiskEngine constructor – add field:
private final ExecutorService pricingPool =
    Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

// recalculate() – replace sequential loop:
List<Callable<Map.Entry<String, RiskAttributes>>> tasks = trades.stream()
    .map(t -> (Callable<Map.Entry<String, RiskAttributes>>)
              () -> Map.entry(t.getTradeId(), pricer.price(t)))
    .toList();

Map<String, RiskAttributes> attributes = new ConcurrentHashMap<>();
for (Future<Map.Entry<String, RiskAttributes>> f : pricingPool.invokeAll(tasks)) {
    Map.Entry<String, RiskAttributes> e = f.get();
    attributes.put(e.getKey(), e.getValue());
    log.debug("  Priced {}: delta={} vega={}",
              e.getKey(),
              String.format("%.2f", e.getValue().delta()),
              String.format("%.2f", e.getValue().vega()));
}
```

### Thread-Safety Notes
- `BlackScholesPricer` — stateless, safe.
- `Trade` fields `spot` and `riskFreeRate` are `volatile` — reads are safe.
- `ConcurrentHashMap` — safe for concurrent puts.

### Expected Gain
Speedup ≈ `min(tradeCount, coreCount)`. On an 8-core machine with 100 trades the pricing
phase should be ~8× faster.

---

## Candidate 2 — Parallel Alert Fanout (Priority: Medium)

### Why
`alertSenders.forEach(s -> s.sendAlert(alert))` fires `EmailSender` and `SymphonySender`
sequentially. Both are stateless, target different external endpoints (SMTP / Symphony REST),
and involve blocking I/O. Total latency is currently the **sum** of all sender RTTs;
parallelised it collapses to the **slowest** single sender.

### Location
`RiskEngine.recalculate()` — alert dispatch block
```java
// BEFORE
alertSenders.forEach(s -> s.sendAlert(alert));
```

### Changes Required
| File | Change |
|---|---|
| `RiskEngine.java` | Reuse `pricingPool` (or a dedicated `alertPool`) to `invokeAll` alert tasks. |

### Implementation Sketch
```java
// Replace sequential forEach:
List<Callable<Void>> alertTasks = alertSenders.stream()
    .map(s -> (Callable<Void>) () -> { s.sendAlert(alert); return null; })
    .toList();
alertPool.invokeAll(alertTasks);   // blocks until all senders complete
```

### Thread-Safety Notes
- `EmailSender` and `SymphonySender` are stateless — safe.
- No shared mutable state between tasks.

### Expected Gain
Medium. Latency = `max(senderRTTs)` instead of `sum(senderRTTs)`.

---

## Candidate 3 — Parallel Limit-Check + Persist Loop (Priority: Medium — needs infra fix first)

### Why
Each bucket's `repo.findLimit()` + `repo.saveResult()` is a synchronous JDBC round-trip and
is logically independent of all other buckets. Parallelising cuts total DB latency from
`O(N × RTT)` to `O(RTT)`.

### Blocker — Single Shared `Connection`
`H2RiskRepository` currently holds one `java.sql.Connection`.
`java.sql.Connection` is **not thread-safe**. Concurrent tasks sharing it will cause statement
interleaving and result-set corruption.

**This candidate must not be implemented until the connection is replaced with a pool.**

### Infrastructure Change Required
Add HikariCP (or equivalent) connection pool to `H2RiskRepository`:
```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
```
```java
// H2RiskRepository — replace single Connection with pool:
private final HikariDataSource ds;

public H2RiskRepository(String jdbcUrl) throws SQLException {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(jdbcUrl);
    cfg.setMaximumPoolSize(8);
    this.ds = new HikariDataSource(cfg);
    ddl();
    seedLimits();
}

// Each method borrows its own connection:
public Optional<RiskLimit> findLimit(String dim, String bucket) throws SQLException {
    try (Connection c = ds.getConnection()) { ... }
}
```

### Location
`RiskEngine.recalculate()` — limit-check + persist loop (after HikariCP is in place)
```java
// AFTER infra fix — parallel bucket processing:
List<Callable<Void>> bucketTasks = aggList.stream()
    .map(agg -> (Callable<Void>) () -> {
        Optional<RiskLimit> limit = repo.findLimit(agg.dimension(), agg.bucketValue());
        boolean breached = limit.map(l ->
                Math.abs(agg.netDelta()) > l.maxAbsNetDelta() ||
                Math.abs(agg.netVega())  > l.maxAbsNetVega())
                .orElse(false);
        if (breached) {
            Alert alert = buildAlert(agg, limit.get());
            dispatchAlerts(alert);   // (already parallelised per Candidate 2)
        }
        repo.saveResult(buildResult(agg, breached));
        return null;
    })
    .toList();
bucketPool.invokeAll(bucketTasks);
```

### Expected Gain
Medium. Each `findLimit` + `saveResult` is I/O-bound. With N buckets and a pool of size P,
latency ≈ `ceil(N/P) × RTT`.

---

## Candidate 4 — Parallel Aggregation by Dimension (Priority: Low–Medium)

### Why
`RiskAggregator.aggregate()` iterates over 3 `AggKey` values (`SECTOR`, `COUNTRY`,
`PORTFOLIO`) sequentially. Each dimension reads from the same read-only `trades` and
`attributes` maps and produces a fully independent `List<AggregatedRisk>`.

### Location
`RiskAggregator.aggregate()`
```java
// BEFORE
for (AggKey dim : AggKey.values()) {
    result.addAll(aggregateByDimension(dim, trades, attributes));
}
```

### Changes Required
| File | Change |
|---|---|
| `RiskAggregator.java` | Accept an `ExecutorService` or use `parallelStream`. Submit one task per `AggKey`. Merge results after all futures complete. |

### Implementation Sketch
```java
// RiskAggregator — inject executor, or use parallelStream:
List<AggregatedRisk> result = Arrays.stream(AggKey.values())
    .parallel()
    .flatMap(dim -> aggregateByDimension(dim, trades, attributes).stream())
    .collect(Collectors.toList());
```

### Thread-Safety Notes
- `trades` and `attributes` are read-only — safe.
- `aggregateByDimension` produces a new list per call — no shared writes.

### Expected Gain
Low–Medium. Maximum parallelism factor = 3 (one per dimension). Worthwhile only at
trade counts in the thousands where `groupingBy` + summation becomes measurable.

---

## Implementation Order

```
Step 1  Candidate 1 — Parallel pricing loop          (no infra changes needed)
Step 2  Candidate 2 — Parallel alert fanout           (no infra changes needed)
Step 3  Candidate 3 — Add HikariCP connection pool    (infra prerequisite)
Step 4  Candidate 3 — Parallel limit-check + persist  (after Step 3)
Step 5  Candidate 4 — Parallel aggregation dimensions (optional, low priority)
```

---

## Shared ExecutorService Strategy

Rather than creating separate pools, a single shared `ExecutorService` can be injected into
`RiskEngine` and `RiskAggregator` at construction time (`RiskEngineMain` wires it up).
This keeps the total thread count bounded and allows graceful shutdown via
`executor.shutdown()` / `executor.awaitTermination()` at JVM exit.

```java
// RiskEngineMain wiring:
ExecutorService executor = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors());

RiskEngine engine = new RiskEngine(cache, pricer, aggregator, repo, alertSenders, executor);

Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    executor.shutdown();
    try { executor.awaitTermination(5, TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
}));
```
