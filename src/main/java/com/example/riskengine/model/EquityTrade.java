package com.example.riskengine.model;

/**
 * Equity option trade — strikePrice/maturityYears on the base Trade are
 * interpreted as the option strike and expiry, priced by BlackScholesPricer.
 */
public class EquityTrade extends Trade {

    public EquityTrade(String tradeId, String underlier, String portfolio,
                        String gics, String country,
                        double quantity, String side,
                        double strikePrice, double maturityYears) {
        super(tradeId, underlier, portfolio, gics, country,
              quantity, side, strikePrice, maturityYears);
    }
}
