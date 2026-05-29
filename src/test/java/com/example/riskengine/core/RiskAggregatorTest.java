package com.example.riskengine.core;

import com.example.riskengine.model.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RiskAggregator.
 *
 * Tests cover:
 *  - Aggregation across all three AggKey dimensions (GICS, COUNTRY, PORTFOLIO)
 *  - Correct netting of delta/gamma/vega/theta across trades in same bucket
 *  - Correct separation of trades in different buckets
 *  - Trade count tracking
 *  - Trades missing from the attributes map are skipped
 *  - Empty inputs produce empty output
 */
@DisplayName("RiskAggregator — aggregation logic")
class RiskAggregatorTest {

    private static final double DELTA = 1e-9;

    private final RiskAggregator aggregator = new RiskAggregator();

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    /** Build a Trade with all fields set. */
    private static Trade trade(String id, String portfolio,
                                String gics, String country,
                                double qty, String side) {
        Trade t = new Trade(id, "TEST", portfolio, gics, country,
                            qty, side, 100.0, 1.0);
        t.setSpot(100.0);
        t.setRiskFreeRate(0.05);
        return t;
    }

    /** Build a RiskAttributes record with explicit values (qty already baked in). */
    private static RiskAttributes ra(String id, double delta, double gamma,
                                      double vega, double theta) {
        return new RiskAttributes(id, 0.20, delta, gamma, vega, theta);
    }

    // ================================================================== //
    //  1. Empty inputs
    // ================================================================== //

    @Test
    @DisplayName("Empty trade list returns empty result")
    void emptyTradeListReturnsEmptyResult() {
        List<AggregatedRisk> result = aggregator.aggregate(
                Collections.emptyList(), Collections.emptyMap());
        assertTrue(result.isEmpty(), "Expected empty list for no trades");
    }

    @Test
    @DisplayName("Empty attributes map returns empty result")
    void emptyAttributesMapReturnsEmptyResult() {
        Trade t = trade("T1", "BOOK_A", "45", "US", 100, "BUY");
        List<AggregatedRisk> result = aggregator.aggregate(
                List.of(t), Collections.emptyMap());
        assertTrue(result.isEmpty(),
            "Trades without matching attributes should produce no buckets");
    }

    // ================================================================== //
    //  2. Single trade — all three dimensions produce exactly one bucket each
    // ================================================================== //

    @Test
    @DisplayName("Single trade produces exactly 3 buckets (one per dimension)")
    void singleTradeProducesThreeBuckets() {
        Trade t = trade("T1", "BOOK_A", "45", "US", 100, "BUY");
        Map<String, RiskAttributes> attrs = Map.of(
                "T1", ra("T1", 55.0, 0.10, 2.0, -0.05));

        List<AggregatedRisk> result = aggregator.aggregate(List.of(t), attrs);

        assertEquals(3, result.size(),
            "One bucket per AggKey dimension expected, got " + result.size());
    }

    @Test
    @DisplayName("Single trade: bucket values match trade fields")
    void singleTradeBucketValuesMatchTradeFields() {
        Trade t = trade("T1", "BOOK_A", "45", "US", 100, "BUY");
        Map<String, RiskAttributes> attrs = Map.of(
                "T1", ra("T1", 55.0, 0.10, 2.0, -0.05));

        List<AggregatedRisk> result = aggregator.aggregate(List.of(t), attrs);

        assertBucketExists(result, AggKey.PORTFOLIO, "BOOK_A");
        assertBucketExists(result, AggKey.GICS,      "45");
        assertBucketExists(result, AggKey.COUNTRY,   "US");
    }

    @Test
    @DisplayName("Single trade: greeks are passed through unchanged")
    void singleTradeGreeksPassedThrough() {
        Trade t = trade("T1", "BOOK_A", "45", "US", 1, "BUY");
        Map<String, RiskAttributes> attrs = Map.of(
                "T1", ra("T1", 0.55, 0.012, 0.25, -0.003));

        List<AggregatedRisk> result = aggregator.aggregate(List.of(t), attrs);
        AggregatedRisk portfolio = findBucket(result, AggKey.PORTFOLIO, "BOOK_A");

        assertAll(
            () -> assertEquals(0.55,   portfolio.netDelta(), DELTA, "netDelta"),
            () -> assertEquals(0.012,  portfolio.netGamma(), DELTA, "netGamma"),
            () -> assertEquals(0.25,   portfolio.netVega(),  DELTA, "netVega"),
            () -> assertEquals(-0.003, portfolio.netTheta(), DELTA, "netTheta"),
            () -> assertEquals(1,      portfolio.tradeCount(),      "tradeCount")
        );
    }

    // ================================================================== //
    //  3. Netting — two trades in the same bucket
    // ================================================================== //

    @Test
    @DisplayName("Two BUY trades in same portfolio: greeks sum correctly")
    void twoTradesSameBucketGreeksSummed() {
        Trade t1 = trade("T1", "BOOK_A", "45", "US", 1, "BUY");
        Trade t2 = trade("T2", "BOOK_A", "45", "US", 1, "BUY");
        Map<String, RiskAttributes> attrs = Map.of(
                "T1", ra("T1", 0.55,  0.010, 0.20, -0.003),
                "T2", ra("T2", 0.45,  0.012, 0.18, -0.002));

        List<AggregatedRisk> result = aggregator.aggregate(List.of(t1, t2), attrs);
        AggregatedRisk portfolio = findBucket(result, AggKey.PORTFOLIO, "BOOK_A");

        assertAll(
            () -> assertEquals(1.00,   portfolio.netDelta(), DELTA, "netDelta"),
            () -> assertEquals(0.022,  portfolio.netGamma(), DELTA, "netGamma"),
            () -> assertEquals(0.38,   portfolio.netVega(),  DELTA, "netVega"),
            () -> assertEquals(-0.005, portfolio.netTheta(), DELTA, "netTheta"),
            () -> assertEquals(2,      portfolio.tradeCount(),      "tradeCount")
        );
    }

    @Test
    @DisplayName("BUY and SELL in same bucket: delta nets to near zero")
    void buyAndSellNetsToZeroDelta() {
        Trade buy  = trade("T1", "BOOK_A", "45", "US", 1, "BUY");
        Trade sell = trade("T2", "BOOK_A", "45", "US", 1, "SELL");
        // Simulate pricer output — SELL delta is negative
        Map<String, RiskAttributes> attrs = Map.of(
                "T1", ra("T1",  0.55, 0.010,  0.20, -0.003),
                "T2", ra("T2", -0.55, 0.010, -0.20,  0.003));

        List<AggregatedRisk> result = aggregator.aggregate(List.of(buy, sell), attrs);
        AggregatedRisk portfolio = findBucket(result, AggKey.PORTFOLIO, "BOOK_A");

        assertEquals(0.0, portfolio.netDelta(), DELTA,
            "Perfectly hedged position should net to zero delta");
    }

    // ================================================================== //
    //  4. Separation — two trades in different buckets
    // ================================================================== //

    @Test
    @DisplayName("Two trades in different portfolios produce separate buckets")
    void twoTradesDifferentPortfoliosSeparateBuckets() {
        Trade t1 = trade("T1", "BOOK_A", "45", "US", 1, "BUY");
        Trade t2 = trade("T2", "BOOK_B", "45", "US", 1, "BUY");
        Map<String, RiskAttributes> attrs = Map.of(
                "T1", ra("T1", 0.55, 0.010, 0.20, -0.003),
                "T2", ra("T2", 0.40, 0.009, 0.18, -0.002));

        List<AggregatedRisk> result = aggregator.aggregate(List.of(t1, t2), attrs);

        AggregatedRisk bookA = findBucket(result, AggKey.PORTFOLIO, "BOOK_A");
        AggregatedRisk bookB = findBucket(result, AggKey.PORTFOLIO, "BOOK_B");

        assertAll(
            () -> assertEquals(0.55, bookA.netDelta(), DELTA, "BOOK_A delta"),
            () -> assertEquals(0.40, bookB.netDelta(), DELTA, "BOOK_B delta"),
            () -> assertEquals(1, bookA.tradeCount(), "BOOK_A tradeCount"),
            () -> assertEquals(1, bookB.tradeCount(), "BOOK_B tradeCount")
        );
    }

    @Test
    @DisplayName("Two trades with different GICS produce separate buckets")
    void twoTradesDifferentGicsSeparateBuckets() {
        Trade t1 = trade("T1", "BOOK_A", "45", "US", 1, "BUY");  // Tech
        Trade t2 = trade("T2", "BOOK_A", "40", "US", 1, "BUY");  // Financials
        Map<String, RiskAttributes> attrs = Map.of(
                "T1", ra("T1", 0.55, 0.010, 0.20, -0.003),
                "T2", ra("T2", 0.40, 0.009, 0.18, -0.002));

        List<AggregatedRisk> result = aggregator.aggregate(List.of(t1, t2), attrs);

        AggregatedRisk tech = findBucket(result, AggKey.GICS, "45");
        AggregatedRisk fin  = findBucket(result, AggKey.GICS, "40");

        assertEquals(0.55, tech.netDelta(), DELTA, "Tech delta");
        assertEquals(0.40, fin.netDelta(),  DELTA, "Financials delta");
    }

    @Test
    @DisplayName("Two trades with different countries produce separate buckets")
    void twoTradesDifferentCountriesSeparateBuckets() {
        Trade t1 = trade("T1", "BOOK_A", "45", "US", 1, "BUY");
        Trade t2 = trade("T2", "BOOK_A", "45", "GB", 1, "BUY");
        Map<String, RiskAttributes> attrs = Map.of(
                "T1", ra("T1", 0.55, 0.010, 0.20, -0.003),
                "T2", ra("T2", 0.40, 0.009, 0.18, -0.002));

        List<AggregatedRisk> result = aggregator.aggregate(List.of(t1, t2), attrs);

        AggregatedRisk us = findBucket(result, AggKey.COUNTRY, "US");
        AggregatedRisk gb = findBucket(result, AggKey.COUNTRY, "GB");

        assertEquals(0.55, us.netDelta(), DELTA, "US delta");
        assertEquals(0.40, gb.netDelta(), DELTA, "GB delta");
    }

    // ================================================================== //
    //  5. Cross-dimension correctness: same trades aggregated consistently
    // ================================================================== //

    @Test
    @DisplayName("Three trades: total netDelta is consistent across dimensions")
    void totalNetDeltaConsistentAcrossDimensions() {
        // Three trades, different portfolios but same GICS + country
        Trade t1 = trade("T1", "BOOK_A", "45", "US", 1, "BUY");
        Trade t2 = trade("T2", "BOOK_B", "45", "US", 1, "BUY");
        Trade t3 = trade("T3", "BOOK_C", "45", "US", 1, "BUY");
        Map<String, RiskAttributes> attrs = Map.of(
                "T1", ra("T1", 0.55, 0.01, 0.2, -0.003),
                "T2", ra("T2", 0.40, 0.01, 0.2, -0.003),
                "T3", ra("T3", 0.60, 0.01, 0.2, -0.003));

        double expectedTotal = 0.55 + 0.40 + 0.60;

        List<AggregatedRisk> result = aggregator.aggregate(
                List.of(t1, t2, t3), attrs);

        // GICS bucket "45" should contain all three
        AggregatedRisk gicsBucket = findBucket(result, AggKey.GICS, "45");
        assertEquals(expectedTotal, gicsBucket.netDelta(), DELTA,
            "GICS bucket netDelta should equal sum of all trades");
        assertEquals(3, gicsBucket.tradeCount(), "GICS bucket tradeCount");

        // COUNTRY bucket "US" should also contain all three
        AggregatedRisk countryBucket = findBucket(result, AggKey.COUNTRY, "US");
        assertEquals(expectedTotal, countryBucket.netDelta(), DELTA,
            "COUNTRY bucket netDelta should equal sum of all trades");
    }

    // ================================================================== //
    //  6. Output structure — all three AggKey values present
    // ================================================================== //

    @Test
    @DisplayName("Result contains entries for all three AggKey dimensions")
    void resultContainsAllThreeDimensions() {
        Trade t = trade("T1", "BOOK_A", "45", "US", 1, "BUY");
        Map<String, RiskAttributes> attrs = Map.of(
                "T1", ra("T1", 0.55, 0.010, 0.20, -0.003));

        List<AggregatedRisk> result = aggregator.aggregate(List.of(t), attrs);

        EnumSet<AggKey> present = EnumSet.noneOf(AggKey.class);
        result.forEach(ar -> present.add(ar.dimension()));

        assertEquals(EnumSet.allOf(AggKey.class), present,
            "All AggKey dimensions should be represented in the output");
    }

    // ================================================================== //
    //  7. Trade missing from attributes map is silently skipped
    // ================================================================== //

    @Test
    @DisplayName("Trade with no attributes entry is excluded from aggregation")
    void tradeMissingAttributesIsExcluded() {
        Trade t1 = trade("T1", "BOOK_A", "45", "US", 1, "BUY");
        Trade t2 = trade("T2", "BOOK_A", "45", "US", 1, "BUY");
        // Only T1 has attributes
        Map<String, RiskAttributes> attrs = Map.of(
                "T1", ra("T1", 0.55, 0.010, 0.20, -0.003));

        List<AggregatedRisk> result = aggregator.aggregate(List.of(t1, t2), attrs);
        AggregatedRisk portfolio = findBucket(result, AggKey.PORTFOLIO, "BOOK_A");

        assertAll(
            () -> assertEquals(0.55, portfolio.netDelta(), DELTA,
                "Only T1 delta should be counted"),
            () -> assertEquals(1, portfolio.tradeCount(),
                "Only T1 should be counted (T2 has no attributes)")
        );
    }

    // ================================================================== //
    //  Assertion helpers
    // ================================================================== //

    private static void assertBucketExists(List<AggregatedRisk> list,
                                            AggKey dim, String bucket) {
        assertTrue(list.stream()
                       .anyMatch(r -> r.dimension() == dim
                                   && r.bucketValue().equals(bucket)),
            "Expected bucket " + dim + "/" + bucket + " not found in results");
    }

    private static AggregatedRisk findBucket(List<AggregatedRisk> list,
                                              AggKey dim, String bucket) {
        return list.stream()
                   .filter(r -> r.dimension() == dim
                             && r.bucketValue().equals(bucket))
                   .findFirst()
                   .orElseThrow(() -> new AssertionError(
                       "Bucket not found: " + dim + "/" + bucket));
    }
}
