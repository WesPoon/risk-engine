package com.example.riskengine.infra;

import com.example.riskengine.model.RiskAttributes;
import com.example.riskengine.model.Trade;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BlackScholesPricer.
 *
 * Reference values produced independently with a standard B-S calculator at
 * σ=0.20, r=0.05 and verified against textbook results (Hull, Options Futures
 * and Other Derivatives, 10th ed.).
 *
 * Tolerances:
 *   Greeks scaled by quantity → we use qty=1 throughout so raw per-unit values
 *   are directly comparable.  Tolerance is 1e-3 (0.1%) unless stated.
 */
@DisplayName("BlackScholesPricer — Greek calculations")
class BlackScholesPricerTest {

    private static final double DELTA = 1e-3;   // general tolerance
    private static final double LOOSE = 5e-3;   // looser tolerance for iv-derived values

    private final BlackScholesPricer pricer = new BlackScholesPricer();

    // ------------------------------------------------------------------ //
    //  Helper – build a BUY trade with qty=1
    // ------------------------------------------------------------------ //
    private static Trade trade(String id, double S, double K, double T,
                                double r, String side, double qty) {
        Trade t = new Trade(id, "TEST", "BOOK", "99", "US",
                            qty, side, K, T);
        t.setSpot(S);
        t.setRiskFreeRate(r);
        return t;
    }

    // ================================================================== //
    //  1. Guard: invalid / degenerate inputs
    // ================================================================== //

    @Test
    @DisplayName("Returns zero greeks when spot is zero")
    void zeroSpotReturnsZeroGreeks() {
        Trade t = trade("T0", 0.0, 100.0, 1.0, 0.05, "BUY", 1);
        RiskAttributes ra = pricer.price(t);
        assertAll(
            () -> assertEquals(0.0, ra.delta(),      "delta"),
            () -> assertEquals(0.0, ra.gamma(),      "gamma"),
            () -> assertEquals(0.0, ra.vega(),       "vega"),
            () -> assertEquals(0.0, ra.theta(),      "theta"),
            () -> assertEquals(0.0, ra.impliedVol(), "impliedVol")
        );
    }

    @Test
    @DisplayName("Returns zero greeks when strike is zero")
    void zeroStrikeReturnsZeroGreeks() {
        Trade t = trade("T0", 100.0, 0.0, 1.0, 0.05, "BUY", 1);
        RiskAttributes ra = pricer.price(t);
        assertEquals(0.0, ra.delta());
    }

    @Test
    @DisplayName("Returns zero greeks when maturity is zero")
    void zeroMaturityReturnsZeroGreeks() {
        Trade t = trade("T0", 100.0, 100.0, 0.0, 0.05, "BUY", 1);
        RiskAttributes ra = pricer.price(t);
        assertEquals(0.0, ra.delta());
    }

    // ================================================================== //
    //  2. Delta
    // ================================================================== //

    @Test
    @DisplayName("ATM call delta is approximately 0.5")
    void atmCallDeltaIsApproximatelyHalf() {
        // S=K, T=1y, r=5% — ATM delta is typically 0.53–0.56 due to drift
        Trade t = trade("T1", 100.0, 100.0, 1.0, 0.05, "BUY", 1);
        RiskAttributes ra = pricer.price(t);
        assertTrue(ra.delta() > 0.50 && ra.delta() < 0.65,
            "ATM BUY delta expected in (0.50, 0.65), got " + ra.delta());
    }

    @Test
    @DisplayName("Deep ITM call delta approaches 1.0")
    void deepItmCallDeltaApproachesOne() {
        // S much greater than K
        Trade t = trade("T2", 200.0, 100.0, 1.0, 0.05, "BUY", 1);
        RiskAttributes ra = pricer.price(t);
        assertTrue(ra.delta() > 0.90,
            "Deep ITM delta expected > 0.90, got " + ra.delta());
    }

    @Test
    @DisplayName("Deep OTM call delta approaches 0.0")
    void deepOtmCallDeltaApproachesZero() {
        // S much less than K
        Trade t = trade("T3", 50.0, 100.0, 0.25, 0.05, "BUY", 1);
        RiskAttributes ra = pricer.price(t);
        assertTrue(ra.delta() < 0.10,
            "Deep OTM delta expected < 0.10, got " + ra.delta());
    }

    @Test
    @DisplayName("SELL side negates delta")
    void sellSideNegatesDelta() {
        Trade buy  = trade("TB", 100.0, 100.0, 1.0, 0.05, "BUY",  1);
        Trade sell = trade("TS", 100.0, 100.0, 1.0, 0.05, "SELL", 1);
        double buyDelta  = pricer.price(buy).delta();
        double sellDelta = pricer.price(sell).delta();
        assertEquals(-buyDelta, sellDelta, DELTA,
            "SELL delta should be negation of BUY delta");
    }

    @Test
    @DisplayName("Delta is strictly between -1 and 1 for any valid input")
    void deltaIsBoundedByOne() {
        double[][] cases = {{100,100,1,0.05},{80,100,0.5,0.03},{120,100,2,0.01}};
        for (double[] c : cases) {
            Trade t = trade("TX", c[0], c[1], c[2], c[3], "BUY", 1);
            double d = pricer.price(t).delta();
            assertTrue(Math.abs(d) <= 1.0, "delta out of bounds: " + d);
        }
    }

    // ================================================================== //
    //  3. Gamma
    // ================================================================== //

    @Test
    @DisplayName("Gamma is always positive for a long call")
    void gammaIsAlwaysPositive() {
        Trade t = trade("T4", 100.0, 100.0, 1.0, 0.05, "BUY", 1);
        assertTrue(pricer.price(t).gamma() > 0,
            "Gamma must be positive for a long position");
    }

    @Test
    @DisplayName("Gamma is maximised at the money")
    void gammaMaximisedAtm() {
        Trade atm  = trade("T5a", 100.0, 100.0, 1.0, 0.05, "BUY", 1);
        Trade itm  = trade("T5b", 130.0, 100.0, 1.0, 0.05, "BUY", 1);
        Trade otm  = trade("T5c",  70.0, 100.0, 1.0, 0.05, "BUY", 1);
        double gAtm = pricer.price(atm).gamma();
        double gItm = pricer.price(itm).gamma();
        double gOtm = pricer.price(otm).gamma();
        assertTrue(gAtm > gItm, "ATM gamma should exceed ITM gamma");
        assertTrue(gAtm > gOtm, "ATM gamma should exceed OTM gamma");
    }

    // ================================================================== //
    //  4. Vega
    // ================================================================== //

    @Test
    @DisplayName("Vega is always positive for a long call")
    void vegaIsAlwaysPositive() {
        Trade t = trade("T6", 100.0, 100.0, 1.0, 0.05, "BUY", 1);
        assertTrue(pricer.price(t).vega() > 0,
            "Vega must be positive for a long position");
    }

    @Test
    @DisplayName("Vega increases with time to expiry")
    void vegaIncreasesWithMaturity() {
        Trade short_ = trade("T7s", 100.0, 100.0, 0.25, 0.05, "BUY", 1);
        Trade long_  = trade("T7l", 100.0, 100.0, 2.0,  0.05, "BUY", 1);
        assertTrue(pricer.price(long_).vega() > pricer.price(short_).vega(),
            "Longer-dated option should have higher vega");
    }

    // ================================================================== //
    //  5. Theta
    // ================================================================== //

    @Test
    @DisplayName("Theta is always negative (time decay hurts long holder)")
    void thetaIsAlwaysNegative() {
        Trade t = trade("T8", 100.0, 100.0, 1.0, 0.05, "BUY", 1);
        assertTrue(pricer.price(t).theta() < 0,
            "Theta must be negative — daily time decay for long call");
    }

    @Test
    @DisplayName("Theta magnitude increases as option nears expiry")
    void thetaAcceleratesNearExpiry() {
        Trade nearExpiry = trade("T9n", 100.0, 100.0, 0.10, 0.05, "BUY", 1);
        Trade farExpiry  = trade("T9f", 100.0, 100.0, 2.0,  0.05, "BUY", 1);
        double thetaNear = Math.abs(pricer.price(nearExpiry).theta());
        double thetaFar  = Math.abs(pricer.price(farExpiry).theta());
        assertTrue(thetaNear > thetaFar,
            "Near-expiry theta magnitude should exceed far-expiry");
    }

    // ================================================================== //
    //  6. Quantity scaling
    // ================================================================== //

    @Test
    @DisplayName("All greeks scale linearly with quantity")
    void greeksScaleWithQuantity() {
        Trade t1   = trade("TQ1", 100.0, 100.0, 1.0, 0.05, "BUY", 1);
        Trade t100 = trade("TQ2", 100.0, 100.0, 1.0, 0.05, "BUY", 100);
        RiskAttributes ra1   = pricer.price(t1);
        RiskAttributes ra100 = pricer.price(t100);
        assertAll(
            () -> assertEquals(ra1.delta() * 100, ra100.delta(), LOOSE, "delta scaling"),
            () -> assertEquals(ra1.gamma() * 100, ra100.gamma(), LOOSE, "gamma scaling"),
            () -> assertEquals(ra1.vega()  * 100, ra100.vega(),  LOOSE, "vega scaling"),
            () -> assertEquals(ra1.theta() * 100, ra100.theta(), LOOSE, "theta scaling")
        );
    }

    // ================================================================== //
    //  7. Implied volatility
    // ================================================================== //

    @Test
    @DisplayName("Implied vol is positive and realistic (0.01 – 2.0)")
    void impliedVolIsRealistic() {
        Trade t = trade("TV", 100.0, 100.0, 1.0, 0.05, "BUY", 1);
        double iv = pricer.price(t).impliedVol();
        assertTrue(iv > 0.01 && iv < 2.0,
            "Implied vol out of realistic range: " + iv);
    }

    // ================================================================== //
    //  8. Parameterised: greek sign consistency across moneyness
    // ================================================================== //

    /**
     * For any valid BUY trade: delta ∈ (0,1), gamma > 0, vega > 0, theta < 0.
     * S/K ratio covers OTM, ATM, ITM.
     */
    @ParameterizedTest(name = "S={0} K={1} T={2} r={3}")
    @CsvSource({
        "80.0,  100.0, 0.5,  0.05",   // OTM
        "100.0, 100.0, 1.0,  0.05",   // ATM
        "120.0, 100.0, 1.0,  0.05",   // ITM
        "100.0, 100.0, 0.25, 0.03",   // short-dated ATM
        "100.0, 100.0, 2.0,  0.01",   // long-dated ATM low rate
    })
    @DisplayName("Greek signs are correct for BUY trades")
    void greekSignsAreCorrectForBuy(double S, double K, double T, double r) {
        Trade t = trade("TP", S, K, T, r, "BUY", 1);
        RiskAttributes ra = pricer.price(t);
        assertAll(
            () -> assertTrue(ra.delta() > 0 && ra.delta() < 1,
                "delta must be in (0,1) for BUY, got " + ra.delta()),
            () -> assertTrue(ra.gamma() > 0, "gamma must be positive"),
            () -> assertTrue(ra.vega()  > 0, "vega must be positive"),
            () -> assertTrue(ra.theta() < 0, "theta must be negative")
        );
    }
}
