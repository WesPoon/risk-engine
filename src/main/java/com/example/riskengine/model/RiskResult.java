package com.example.riskengine.model;

import java.time.Instant;

/**
 * Persisted calculation result for audit / reporting.
 */
public record RiskResult(
        AggKey dimension,
        String bucketValue,
        double netDelta,
        double netGamma,
        double netVega,
        double netTheta,
        boolean limitBreached,
        Instant calculatedAt
) {}
