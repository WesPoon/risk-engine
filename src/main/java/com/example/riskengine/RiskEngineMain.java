package com.example.riskengine;

import com.example.riskengine.core.RiskAggregator;
import com.example.riskengine.core.RiskEngine;
import com.example.riskengine.infra.*;
import com.example.riskengine.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;

/**
 * Wires up all components and fires a simulated stream of market events
 * to demonstrate the end-to-end risk-engine flow.
 *
 * No real Kafka or Redis required – the simulation uses InMemoryTradeCache
 * and feeds RiskEvent objects directly into RiskEngine.onUpdate().
 */
public class RiskEngineMain {

    private static final Logger log = LoggerFactory.getLogger(RiskEngineMain.class);

    public static void main(String[] args) throws SQLException, InterruptedException {

        log.info("=== Risk Engine starting up ===");

        // ---- Infrastructure ----
        InMemoryTradeCache cache = new InMemoryTradeCache();
        BlackScholesPricer pricer = new BlackScholesPricer();
        RiskAggregator aggregator = new RiskAggregator();
        H2RiskRepository repo = new H2RiskRepository(
                "jdbc:h2:mem:riskdb;DB_CLOSE_DELAY=-1");

        List<AlertSender> alertSenders = List.of(
                new EmailSender("risk-desk@example.com"),
                new SymphonySender("risk-alerts-room-42"));

        // ---- Engine ----
        RiskEngine engine = new RiskEngine(cache, pricer, aggregator, repo, alertSenders);
        engine.onSubscribe("positions");
        engine.onSubscribe("spots");

        // ================================================================
        //  Simulation: batch of position updates followed by spot updates
        // ================================================================

        // Events are batched: recalculate() fires every 10 events, or on flushBatch().
        // Each phase below ends with an explicit flushBatch() to drain any partial batch.

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
        engine.flushBatch();  // flush partial batch of 6

        log.info("\n--- Phase 2: Spot updates — 4 events → flush ---");
        engine.onUpdate(RiskEvent.ofSpot(new SpotUpdate("AAPL", 162.50, 0.05,
                System.currentTimeMillis())));
        engine.onUpdate(RiskEvent.ofSpot(new SpotUpdate("MSFT", 315.00, 0.05,
                System.currentTimeMillis())));
        engine.onUpdate(RiskEvent.ofSpot(new SpotUpdate("JPM",   88.00, 0.05,
                System.currentTimeMillis())));
        engine.onUpdate(RiskEvent.ofSpot(new SpotUpdate("SHEL",  26.50, 0.05,
                System.currentTimeMillis())));
        engine.flushBatch();  // flush partial batch of 4

        log.info("\n--- Phase 3: Large position that should breach BOOK_A delta limit ---");
        engine.onUpdate(RiskEvent.ofPosition(new PositionUpdate(
                "T007", "GOOGL", "BOOK_A", 50_000, "BUY", 140.0, 2.0)));
        engine.onUpdate(RiskEvent.ofSpot(new SpotUpdate("GOOGL", 145.0, 0.05,
                System.currentTimeMillis())));
        engine.flushBatch();  // flush partial batch of 2

        // ================================================================
        //  Report
        // ================================================================

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
}
