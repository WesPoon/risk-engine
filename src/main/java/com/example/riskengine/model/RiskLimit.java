package com.example.riskengine.model;

/**
 * Regulatory / internal limit stored in SQL, keyed by dimension + bucket.
 */
public record RiskLimit(
        long id,
        AggKey dimension,
        String bucketValue,
        double maxAbsNetDelta,
        double maxAbsNetVega
) {}
