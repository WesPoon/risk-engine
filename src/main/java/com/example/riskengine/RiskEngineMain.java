package com.example.riskengine;

import com.example.riskengine.core.RiskAggregator;
import com.example.riskengine.core.RiskEngine;
import com.example.riskengine.infra.*;
import com.example.riskengine.model.*;
import com.example.riskengine.pricer.BlackScholesPricer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

/**
 * Wires all components and either:
 *  - runs a Kafka consumer (if KAFKA_BOOTSTRAP_SERVERS is set), or
 *  - runs the built-in simulation for local testing.
 */
public class RiskEngineMain {

    private static final Logger log = LoggerFactory.getLogger(RiskEngineMain.class);

    public static void main(String[] args) throws SQLException, InterruptedException {

        log.info("=== Risk Engine starting up ===");

        // ---- Infrastructure ----
        InMemoryTradeCache cache      = new InMemoryTradeCache();
        BlackScholesPricer pricer     = new BlackScholesPricer();
        RiskAggregator     aggregator = new RiskAggregator();
        H2RiskRepository   repo       = new H2RiskRepository(
                "jdbc:h2:mem:riskdb;DB_CLOSE_DELAY=-1");

        List<AlertSender> alertSenders = List.of(
                new EmailSender("risk-desk@example.com"),
                new SymphonySender("risk-alerts-room-42"));

        // ---- Engine ----
        RiskEngine engine = new RiskEngine(cache, pricer, aggregator, repo, alertSenders);

        String kafkaBootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (kafkaBootstrap != null && !kafkaBootstrap.isBlank()) {
            runWithKafka(engine, kafkaBootstrap);
        } else {
            runSimulation(engine, repo);
        }
    }

    // ------------------------------------------------------------------ //
    //  Kafka mode
    // ------------------------------------------------------------------ //

    private static void runWithKafka(RiskEngine engine, String bootstrapServers)
            throws InterruptedException {
        log.info("Kafka mode — bootstrap.servers={}", bootstrapServers);

        Properties kafkaProps = new Properties();
        kafkaProps.put("bootstrap.servers",  bootstrapServers);
        kafkaProps.put("group.id",           env("KAFKA_GROUP_ID", "risk-engine"));
        kafkaProps.put("auto.offset.reset",   env("KAFKA_OFFSET_RESET", "earliest"));
        kafkaProps.put("enable.auto.commit",  env("KAFKA_ENABLE_AUTO_COMMIT", "false"));   // manual commit for exactly-once
        kafkaProps.put("isolation.level",     env("KAFKA_ISOLATION_LEVEL", "read_committed")); // skip aborted txn messages
        kafkaProps.put("key.deserializer",
                env("KAFKA_KEY_DESERIALIZER",
                        "org.apache.kafka.common.serialization.StringDeserializer"));
        kafkaProps.put("value.deserializer",
                env("KAFKA_VALUE_DESERIALIZER",
                        "org.apache.kafka.common.serialization.StringDeserializer"));

        KafkaRiskConsumer consumer = new KafkaRiskConsumer(engine, kafkaProps);
        consumer.subscribe();
        consumer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — stopping Kafka consumer");
            consumer.stop();
        }));

        // Block the main thread; the poll loop runs on a daemon thread.
        Thread.currentThread().join();
    }

    // ------------------------------------------------------------------ //
    //  Simulation mode (no Kafka broker required)
    // ------------------------------------------------------------------ //

    private static void runSimulation(RiskEngine engine, H2RiskRepository repo)
            throws SQLException {
        log.info("Simulation mode — no KAFKA_BOOTSTRAP_SERVERS set");

        engine.onSubscribe("positions");
        engine.onSubscribe("spots");

        log.info("\n--- Phase 1: Seed initial positions (6 events → flush) ---");
        engine.onUpdate(RiskEvent.ofPosition(new PositionUpdate(
                "T001", "AAPL", "BOOK_A", 1_000, "BUY",  150.0, 0.5)));
        engine.onUpdate(RiskEvent.ofPosition(new PositionUpdate(
                "T002", "AAPL", "BOOK_A",   500, "SELL", 155.0, 0.25)));
        engine.onUpdate(RiskEvent.ofPosition(new PositionUpdate(
                "T003", "MSFT", "BOOK_A",   800, "BUY",  300.0, 1.0)));
        engine.onUpdate(RiskEvent.ofPosition(new PositionUpdate(
                "T004", "JPM",  "BOOK_B",   600, "BUY",   90.0, 0.75)));
        engine.onUpdate(RiskEvent.ofPosition(new PositionUpdate(
                "T005", "JPM",  "BOOK_B",   400, "SELL",  95.0, 0.5)));
        engine.onUpdate(RiskEvent.ofPosition(new PositionUpdate(
                "T006", "SHEL", "BOOK_B",   200, "BUY",   25.0, 1.0)));
        engine.flushBatch();

        log.info("\n--- Phase 2: Spot updates — 4 events → flush ---");
        engine.onUpdate(RiskEvent.ofSpot(new SpotUpdate("AAPL", 162.50, 0.05,
                System.currentTimeMillis())));
        engine.onUpdate(RiskEvent.ofSpot(new SpotUpdate("MSFT", 315.00, 0.05,
                System.currentTimeMillis())));
        engine.onUpdate(RiskEvent.ofSpot(new SpotUpdate("JPM",   88.00, 0.05,
                System.currentTimeMillis())));
        engine.onUpdate(RiskEvent.ofSpot(new SpotUpdate("SHEL",  26.50, 0.05,
                System.currentTimeMillis())));
        engine.flushBatch();

        log.info("\n--- Phase 3: Large position that should breach BOOK_A delta limit ---");
        engine.onUpdate(RiskEvent.ofPosition(new PositionUpdate(
                "T007", "GOOGL", "BOOK_A", 50_000, "BUY", 140.0, 2.0)));
        engine.onUpdate(RiskEvent.ofSpot(new SpotUpdate("GOOGL", 145.0, 0.05,
                System.currentTimeMillis())));
        engine.flushBatch();

        log.info("\n=== Persisted Risk Results ===");
        repo.findAllResults().stream()
            .filter(RiskResult::limitBreached)
            .forEach(r -> log.warn("BREACH  {}", r));

        log.info("\n--- All results ({} rows) ---", repo.findAllResults().size());
        repo.findAllResults().forEach(r ->
                log.info("  {}/{} | netDelta={} netVega={} breach={}",
                        r.dimension(), r.bucketValue(),
                        String.format("%.1f", r.netDelta()),
                        String.format("%.1f", r.netVega()),
                        r.limitBreached()));

        log.info("\n=== Risk Engine simulation complete ===");
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
