package com.example.riskengine.model;

/**
 * Net risk across all trades in one aggregation bucket (e.g. GICS=40 Tech).
 */
public record AggregatedRisk(
        AggKey dimension,
        String bucketValue,
        double netDelta,
        double netGamma,
        double netVega,
        double netTheta,
        int tradeCount
) {}
