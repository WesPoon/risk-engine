package com.example.riskengine.model;

/**
 * Inbound Kafka event: a new spot price for an underlying.
 */
public record SpotUpdate(
        String underlier,
        double spotPrice,
        double riskFreeRate,
        long timestampMs
) {}
