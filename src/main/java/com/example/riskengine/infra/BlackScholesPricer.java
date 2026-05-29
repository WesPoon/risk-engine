package com.example.riskengine.infra;

import com.example.riskengine.Pricer;
import com.example.riskengine.model.RiskAttributes;
import com.example.riskengine.model.Trade;

/**
 * Black-Scholes European call option pricer.
 *
 * Computes:
 *   d1 = (ln(S/K) + (r + σ²/2)·T) / (σ·√T)
 *   d2 = d1 - σ·√T
 *   delta = N(d1)   (for a long call; negated for put)
 *   gamma = N'(d1) / (S·σ·√T)
 *   vega  = S·N'(d1)·√T  / 100
 *   theta = -(S·N'(d1)·σ)/(2√T) / 365  (per day)
 *
 * Implied vol is approximated from a simple Newton-Raphson loop vs. ATM
 * rule-of-thumb (σ₀ = 0.20) when spot/strike ratio is close to 1.
 */
public class BlackScholesPricer implements Pricer {

    @Override
    public RiskAttributes price(Trade trade) {
        double S = trade.getSpot();
        double K = trade.getStrikePrice();
        double T = trade.getMaturityYears();
        double r = trade.getRiskFreeRate();

        if (S <= 0 || K <= 0 || T <= 0) {
            return new RiskAttributes(trade.getTradeId(), 0, 0, 0, 0, 0);
        }

        double sigma = impliedVol(S, K, T, r);
        double sqrtT = Math.sqrt(T);
        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;

        double sign = "SELL".equalsIgnoreCase(trade.getSide()) ? -1.0 : 1.0;
        double delta = sign * normCdf(d1);
        double gamma = normPdf(d1) / (S * sigma * sqrtT);
        double vega  = S * normPdf(d1) * sqrtT / 100.0;
        double theta = -(S * normPdf(d1) * sigma) / (2.0 * sqrtT) / 365.0;

        // scale by quantity
        double qty = trade.getQuantity();
        return new RiskAttributes(trade.getTradeId(),
                sigma,
                delta * qty,
                gamma * qty,
                vega  * qty,
                theta * qty);
    }

    // ------------------------------------------------------------------ //
    //  Numerics
    // ------------------------------------------------------------------ //

    /**
     * Closed-form implied vol approximation — no external market price required.
     *
     * Uses a flat base vol of 20 % plus a vol-smile proxy that adds 5 % per
     * 10 % of log-moneyness away from ATM (mimics a simplified skew surface):
     *
     *   σ = 0.20 + 0.50 · |ln(S/K)|
     *
     * For ATM options (S≈K) this gives σ ≈ 0.20 (20 %).
     * For deep OTM/ITM it rises smoothly, keeping all Greeks well-defined.
     *
     * The previous Newton-Raphson approach was unstable because the heuristic
     * objective  callPrice = 0.4·σ·S·√T  has no reliable fixed point for
     * non-ATM inputs, causing sigma to collapse to the minimum clamp.
     */
    private static double impliedVol(double S, double K, double T, double r) {
        double moneyness = Math.abs(Math.log(S / K));
        double sigma     = 0.20 + 0.50 * moneyness;
        // Cap at a realistic maximum; floor at 1 %
        return Math.max(0.01, Math.min(3.0, sigma));
    }

    /** Standard normal PDF. */
    private static double normPdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
    }

    /** Standard normal CDF via Hart approximation. */
    private static double normCdf(double x) {
        if (x < -10) return 0;
        if (x >  10) return 1;
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double poly = t * (0.319381530
                + t * (-0.356563782
                + t * (1.781477937
                + t * (-1.821255978
                + t *  1.330274429))));
        double pdf = normPdf(x);
        double cdf = 1.0 - pdf * poly;
        return x >= 0 ? cdf : 1.0 - cdf;
    }
}
