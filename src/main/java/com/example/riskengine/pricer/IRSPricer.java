package com.example.riskengine.pricer;

import com.example.riskengine.model.RiskAttributes;
import com.example.riskengine.model.Trade;

/**
 * Flat-curve interest rate swap (IRS) pricer.
 *
 * Trade has no IRS-specific fields, so the existing fields are reused:
 *   notional     = quantity
 *   fixedRate    = strikePrice     (decimal, e.g. 0.045 for 4.5%)
 *   floatingRate = riskFreeRate    (flat floating/discount rate)
 *   tenorYears   = maturityYears
 *   side         = BUY  → pay-fixed / receive-floating (payer swap)
 *                  SELL → receive-fixed / pay-floating (receiver swap)
 *
 * Computes:
 *   annuity A   = Σ_{i=1..N} exp(-floatingRate · i)   (annual accrual dates, N = round(tenorYears))
 *   PV          = sign · notional · (floatingRate - fixedRate) · A
 *   delta (DV01)= sign · notional · A · 0.0001         (PV impact of a 1bp parallel rate move)
 *   gamma, vega = 0                                     (linear product, no optionality)
 *   theta       = sign · notional · (floatingRate - fixedRate) / 365   (daily carry)
 *
 * This ignores second-order sensitivity of the annuity itself to rate moves
 * and multi-curve (OIS vs. IBOR) discounting — acceptable for a delta-normal
 * risk approximation, not a production swap pricer.
 */
public class IRSPricer implements Pricer {

    @Override
    public RiskAttributes price(Trade trade) {
        double notional     = trade.getQuantity();
        double fixedRate    = trade.getStrikePrice();
        double floatingRate = trade.getRiskFreeRate();
        double tenorYears   = trade.getMaturityYears();

        if (notional == 0 || tenorYears <= 0) {
            return new RiskAttributes(trade.getTradeId(), 0, 0, 0, 0, 0, 0);
        }

        double sign = "SELL".equalsIgnoreCase(trade.getSide()) ? -1.0 : 1.0;

        int periods = Math.max(1, (int) Math.round(tenorYears));
        double annuity = 0.0;
        for (int i = 1; i <= periods; i++) {
            annuity += Math.exp(-floatingRate * i);
        }

        double rateDiff = floatingRate - fixedRate;
        double dv01  = priceDV01(sign, notional, annuity);
        double theta = sign * notional * rateDiff / 365.0;

        return new RiskAttributes(trade.getTradeId(),
                0.0,   // impliedVol — not applicable to a linear rates product
                dv01,
                0.0,   // gamma — linear product, no convexity in this approximation
                0.0,   // vega  — no optionality
                theta,
                0.0);  // vanna — n/a
    }

    public double priceDV01(double sign, double notional, double annuity){
        return sign * notional * annuity * 0.0001;

    }
    
}
