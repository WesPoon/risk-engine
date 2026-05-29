package com.example.riskengine.core;

import com.example.riskengine.*;
import com.example.riskengine.infra.H2RiskRepository;
import com.example.riskengine.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * Central orchestrator.
 *
 * Flow per event:
 *  1. Receive RiskEvent (PositionUpdate or SpotUpdate) via onUpdate()
 *  2. Event is buffered into a pendingBatch queue (max BATCH_SIZE = 10)
 *  3. When the batch is full (or flushBatch() is called), all buffered
 *     PositionUpdates and SpotUpdates are applied to TradeCache in one go
 *  4. recalculate() is invoked once per batch — not once per event
 *  5. For each trade: call Pricer → RiskAttributes
 *  6. RiskAggregator → List<AggregatedRisk>
 *  7. Compare vs RiskLimit from H2
 *  8. If breached → send Alert via all AlertSenders
 *  9. Persist RiskResult to H2
 */
public class RiskEngine implements RiskObserver {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    /** Number of events to accumulate before triggering recalculate(). */
    private static final int BATCH_SIZE = 10;

    private final TradeCache cache;
    private final Pricer pricer;
    private final RiskAggregator aggregator;
    private final H2RiskRepository repo;
    private final List<AlertSender> alertSenders;

    /** Pending event batch — flushed every BATCH_SIZE events or on explicit flushBatch(). */
    private final Queue<RiskEvent> pendingBatch = new ArrayDeque<>(BATCH_SIZE);

    public RiskEngine(TradeCache cache,
                      Pricer pricer,
                      RiskAggregator aggregator,
                      H2RiskRepository repo,
                      List<AlertSender> alertSenders) {
        this.cache        = cache;
        this.pricer       = pricer;
        this.aggregator   = aggregator;
        this.repo         = repo;
        this.alertSenders = alertSenders;
    }

    // ------------------------------------------------------------------ //
    //  RiskObserver
    // ------------------------------------------------------------------ //

    @Override
    public void onSubscribe(String topic) {
        log.info("Subscribed to topic: {}", topic);
    }

    /**
     * Buffers the event into the pending batch.
     * Once BATCH_SIZE (10) events have been queued the batch is flushed
     * automatically: all events are applied to the cache and recalculate()
     * is called exactly once for the whole batch.
     */
    @Override
    public void onUpdate(RiskEvent event) {
        pendingBatch.add(event);
        log.debug("Queued event (batch {}/{}): type={}",
                  pendingBatch.size(), BATCH_SIZE, event.type());

        if (pendingBatch.size() >= BATCH_SIZE) {
            flushBatch();
        }
    }

    /**
     * Applies all queued events to the cache and triggers a single
     * recalculate() pass. Call this explicitly after the last event in a
     * sequence to ensure no events remain buffered.
     */
    public void flushBatch() {
        if (pendingBatch.isEmpty()) return;

        log.info("Flushing batch of {} events", pendingBatch.size());
        RiskEvent event;
        while ((event = pendingBatch.poll()) != null) {
            switch (event.type()) {
                case POSITION_UPDATE -> handlePosition(event.positionUpdate());
                case SPOT_UPDATE     -> handleSpot(event.spotUpdate());
            }
        }
        // Single recalculate() for the entire batch — not one per event
        recalculate();
    }

    // ------------------------------------------------------------------ //
    //  Event handlers
    // ------------------------------------------------------------------ //

    // handlePosition and handleSpot now only mutate the cache — recalculate()
    // is called once per batch by flushBatch(), not once per event.

    private void handlePosition(PositionUpdate pu) {
        log.info("→ PositionUpdate: tradeId={} underlier={}", pu.tradeId(), pu.underlier());

        Trade trade = cache.getTrade(pu.tradeId()).orElseGet(() -> {
            // Derive enrichment data – in production this would query a reference-data service
            String gics    = deriveGics(pu.underlier());
            String country = deriveCountry(pu.underlier());
            Trade t = new Trade(pu.tradeId(), pu.underlier(), pu.portfolio(),
                                gics, country,
                                pu.quantity(), pu.side(),
                                pu.strikePrice(), pu.maturityYears());
            t.setSpot(pu.strikePrice());   // use strike as initial spot if no SpotUpdate yet
            t.setRiskFreeRate(0.05);
            log.debug("  New trade inserted into cache: {}", t);
            return t;
        });
        // Always update mutable fields
        trade.setSpot(trade.getSpot() == 0 ? pu.strikePrice() : trade.getSpot());
        cache.putTrade(trade);
        // recalculate() is NOT called here — deferred to flushBatch()
    }

    private void handleSpot(SpotUpdate su) {
        log.info("→ SpotUpdate: underlier={} spot={}", su.underlier(), su.spotPrice());
        cache.getTradesByUnderlier(su.underlier()).forEach(t -> {
            t.setSpot(su.spotPrice());
            t.setRiskFreeRate(su.riskFreeRate());
            cache.putTrade(t);
            log.debug("  Updated spot for trade {}: spot={}", t.getTradeId(), su.spotPrice());
        });
        // recalculate() is NOT called here — deferred to flushBatch()
    }

    // ------------------------------------------------------------------ //
    //  Core calculation loop
    // ------------------------------------------------------------------ //

    private void recalculate() {
        List<Trade> trades = cache.getAllTrades();
        if (trades.isEmpty()) return;

        // Step 3 – price every trade
        Map<String, RiskAttributes> attributes = new LinkedHashMap<>();
        for (Trade t : trades) {
            RiskAttributes ra = pricer.price(t);
            attributes.put(t.getTradeId(), ra);
            log.debug("  Priced {}: delta={} vega={}",
                      t.getTradeId(),
                      String.format("%.2f", ra.delta()),
                      String.format("%.2f", ra.vega()));
        }

        // Step 4 – aggregate
        List<AggregatedRisk> aggList = aggregator.aggregate(trades, attributes);
        log.info("  Aggregated {} buckets", aggList.size());

        // Steps 5-7
        for (AggregatedRisk agg : aggList) {
            try {
                Optional<RiskLimit> limit = repo.findLimit(agg.dimension(), agg.bucketValue());
                boolean breached = limit.map(l ->
                        Math.abs(agg.netDelta()) > l.maxAbsNetDelta() ||
                        Math.abs(agg.netVega())  > l.maxAbsNetVega())
                        .orElse(false);

                if (breached) {
                    RiskLimit l = limit.get();
                    Alert alert = new Alert(agg.dimension(), agg.bucketValue(),
                            agg.netDelta(), l.maxAbsNetDelta(),
                            agg.netVega(),  l.maxAbsNetVega(),
                            Instant.now());
                    alertSenders.forEach(s -> s.sendAlert(alert));
                }

                RiskResult result = new RiskResult(
                        agg.dimension(), agg.bucketValue(),
                        agg.netDelta(), agg.netGamma(),
                        agg.netVega(),  agg.netTheta(),
                        breached, Instant.now());
                repo.saveResult(result);

            } catch (SQLException e) {
                log.error("DB error for bucket {}/{}: {}",
                          agg.dimension(), agg.bucketValue(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Reference-data stubs
    // ------------------------------------------------------------------ //

    private static String deriveGics(String underlier) {
        return switch (underlier) {
            case "AAPL", "MSFT", "GOOGL" -> "45";   // Information Technology
            case "JPM",  "GS",   "BAC"   -> "40";   // Financials
            default                       -> "99";   // Other
        };
    }

    private static String deriveCountry(String underlier) {
        return switch (underlier) {
            case "SHEL", "AZN"           -> "GB";
            default                      -> "US";
        };
    }
}
