package com.example.riskengine.model;

/**
 * Option greeks + implied vol computed by the Pricer for a single trade.
 */
public record RiskAttributes(
        String tradeId,
        double impliedVol,
        double delta,
        double gamma,
        double vega,
        double theta
) {}
