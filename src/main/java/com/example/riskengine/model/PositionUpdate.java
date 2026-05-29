package com.example.riskengine.model;

/**
 * Inbound Kafka event: a new or changed position.
 */
public record PositionUpdate(
        String tradeId,
        String underlier,
        String portfolio,
        double quantity,
        String side,          // BUY | SELL
        double strikePrice,
        double maturityYears
) {}
