package com.example.riskengine.infra;

import com.example.riskengine.TradeCache;
import com.example.riskengine.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of TradeCache backed by a ConcurrentHashMap.
 * Suitable for simulation and unit tests (no Redis required).
 *
 * Capacity guard: rejects new trades once MAX_POSITIONS is reached to
 * prevent heap exhaustion. At ~300 bytes per Trade object plus
 * ConcurrentHashMap overhead, 1 M entries ≈ 330 MB — beyond that the
 * risk of OOM on a standard Fargate 1 GB task is too high.
 *
 * Secondary index: underlierIndex maps underlier → Set<tradeId> so that
 * getTradesByUnderlier() is O(1) instead of a full O(N) scan.
 */
public class InMemoryTradeCache implements TradeCache {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTradeCache.class);

    /** Hard cap – reject putTrade() for net-new trades beyond this limit. */
    static final int MAX_POSITIONS = 1_000_000;

    /** Primary store: tradeId → Trade. */
    private final Map<String, Trade> store = new ConcurrentHashMap<>();

    /** Secondary index: underlier → Set of tradeIds (O(1) lookup). */
    private final Map<String, Set<String>> underlierIndex = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ //

    @Override
    public void putTrade(Trade trade) {
        String tradeId   = trade.getTradeId();
        boolean isNew    = !store.containsKey(tradeId);

        // Enforce cap only for brand-new trades, not updates to existing ones
        if (isNew && store.size() >= MAX_POSITIONS) {
            log.error("Cache capacity reached ({} positions). Rejecting new trade: {}",
                      MAX_POSITIONS, tradeId);
            throw new IllegalStateException(
                "TradeCache is full: maximum " + MAX_POSITIONS + " positions reached. " +
                "Migrate to a Redis-backed cache for larger volumes.");
        }

        String underlier = trade.getUnderlier();

        // Remove from old underlier index if underlier changed on an update
        if (isNew) {
            underlierIndex
                .computeIfAbsent(underlier, k -> ConcurrentHashMap.newKeySet())
                .add(tradeId);
        } else {
            String oldUnderlier = store.get(tradeId).getUnderlier();
            if (!oldUnderlier.equals(underlier)) {
                // Underlier changed — update secondary index
                Set<String> oldSet = underlierIndex.get(oldUnderlier);
                if (oldSet != null) oldSet.remove(tradeId);
                underlierIndex
                    .computeIfAbsent(underlier, k -> ConcurrentHashMap.newKeySet())
                    .add(tradeId);
            }
        }

        store.put(tradeId, trade);
    }

    @Override
    public Optional<Trade> getTrade(String tradeId) {
        return Optional.ofNullable(store.get(tradeId));
    }

    /**
     * O(1) lookup via secondary underlier index — no full scan.
     */
    @Override
    public List<Trade> getTradesByUnderlier(String underlier) {
        Set<String> ids = underlierIndex.get(underlier);
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        List<Trade> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            Trade t = store.get(id);
            if (t != null) result.add(t);
        }
        return result;
    }

    @Override
    public List<Trade> getAllTrades() {
        return new ArrayList<>(store.values());
    }

    /** Convenience accessor for monitoring / metrics. */
    public int size() {
        return store.size();
    }
}
