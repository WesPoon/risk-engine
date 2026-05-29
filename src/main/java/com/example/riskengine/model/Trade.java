package com.example.riskengine.model;

/**
 * Enriched trade record stored in Redis after initial position update.
 */
public class Trade {
    private final String tradeId;
    private final String underlier;
    private final String portfolio;
    private final String gics;
    private final String country;
    private final double quantity;
    private final String side;
    private final double strikePrice;
    private final double maturityYears;
    // spot price & risk-free rate, refreshed on every SpotUpdate
    private volatile double spot;
    private volatile double riskFreeRate;

    public Trade(String tradeId, String underlier, String portfolio,
                 String gics, String country,
                 double quantity, String side,
                 double strikePrice, double maturityYears) {
        this.tradeId = tradeId;
        this.underlier = underlier;
        this.portfolio = portfolio;
        this.gics = gics;
        this.country = country;
        this.quantity = quantity;
        this.side = side;
        this.strikePrice = strikePrice;
        this.maturityYears = maturityYears;
    }

    // ---- getters ----
    public String getTradeId()       { return tradeId; }
    public String getUnderlier()     { return underlier; }
    public String getPortfolio()     { return portfolio; }
    public String getGics()          { return gics; }
    public String getCountry()       { return country; }
    public double getQuantity()      { return quantity; }
    public String getSide()          { return side; }
    public double getStrikePrice()   { return strikePrice; }
    public double getMaturityYears() { return maturityYears; }
    public double getSpot()          { return spot; }
    public double getRiskFreeRate()  { return riskFreeRate; }

    // ---- mutable market data ----
    public void setSpot(double spot)               { this.spot = spot; }
    public void setRiskFreeRate(double rfr)        { this.riskFreeRate = rfr; }

    @Override
    public String toString() {
        return "Trade{id=%s, und=%s, port=%s, qty=%.0f, spot=%.2f}"
                .formatted(tradeId, underlier, portfolio, quantity, spot);
    }
}
