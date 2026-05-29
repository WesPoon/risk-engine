package com.example.riskengine;

import com.example.riskengine.model.Trade;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Abstraction over the Redis cache.
 * In production: backed by Jedis. In tests: backed by an in-memory map.
 */
public interface TradeCache {
    void putTrade(Trade trade);
    Optional<Trade> getTrade(String tradeId);
    List<Trade> getTradesByUnderlier(String underlier);
    List<Trade> getAllTrades();
}
