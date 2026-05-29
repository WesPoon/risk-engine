package com.example.riskengine.core;

import com.example.riskengine.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Groups a list of (Trade, RiskAttributes) pairs into AggregatedRisk
 * for each combination of AggKey dimension × bucket value.
 */
public class RiskAggregator {

    /**
     * @param trades     all enriched trades
     * @param attributes map  tradeId → RiskAttributes
     * @return flat list of aggregated buckets across all three AggKey dimensions
     */
    public List<AggregatedRisk> aggregate(
            List<com.example.riskengine.model.Trade> trades,
            Map<String, RiskAttributes> attributes) {

        List<AggregatedRisk> result = new ArrayList<>();
        for (AggKey dim : AggKey.values()) {
            result.addAll(aggregateByDimension(dim, trades, attributes));
        }
        return result;
    }

    private List<AggregatedRisk> aggregateByDimension(
            AggKey dim,
            List<com.example.riskengine.model.Trade> trades,
            Map<String, RiskAttributes> attributes) {

        // Group trade ids by bucket value for this dimension
        Map<String, List<com.example.riskengine.model.Trade>> grouped = trades.stream()
                .filter(t -> attributes.containsKey(t.getTradeId()))
                .collect(Collectors.groupingBy(t -> bucketValue(dim, t)));

        List<AggregatedRisk> out = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            String bucket = entry.getKey();
            double netDelta = 0, netGamma = 0, netVega = 0, netTheta = 0;
            for (var t : entry.getValue()) {
                RiskAttributes ra = attributes.get(t.getTradeId());
                netDelta += ra.delta();
                netGamma += ra.gamma();
                netVega  += ra.vega();
                netTheta += ra.theta();
            }
            out.add(new AggregatedRisk(dim, bucket,
                    netDelta, netGamma, netVega, netTheta,
                    entry.getValue().size()));
        }
        return out;
    }

    private static String bucketValue(AggKey dim,
                                      com.example.riskengine.model.Trade t) {
        return switch (dim) {
            case GICS      -> t.getGics();
            case COUNTRY   -> t.getCountry();
            case PORTFOLIO -> t.getPortfolio();
        };
    }
}
