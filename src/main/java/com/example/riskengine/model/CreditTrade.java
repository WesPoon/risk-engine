package com.example.riskengine.model;

/**
 * Credit trade (bond / CDS position) — strikePrice on the base Trade is
 * interpreted as the coupon rate and maturityYears as the bond/CDS tenor.
 * Adds the credit-specific inputs needed to price spread and default risk.
 */
public class CreditTrade extends Trade {

    private final double creditSpreadBps;
    private final double recoveryRate;

    public CreditTrade(String tradeId, String underlier, String portfolio,
                        String gics, String country,
                        double quantity, String side,
                        double couponRate, double maturityYears,
                        double creditSpreadBps, double recoveryRate) {
        super(tradeId, underlier, portfolio, gics, country,
              quantity, side, couponRate, maturityYears);
        this.creditSpreadBps = creditSpreadBps;
        this.recoveryRate = recoveryRate;
    }

    public double getCreditSpreadBps() { return creditSpreadBps; }
    public double getRecoveryRate()    { return recoveryRate; }
}
