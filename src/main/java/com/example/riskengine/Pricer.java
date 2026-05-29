package com.example.riskengine;

import com.example.riskengine.model.RiskAttributes;
import com.example.riskengine.model.Trade;

/**
 * Computes implied vol, delta and other greeks for a single trade.
 */
public interface Pricer {
    RiskAttributes price(Trade trade);
}
