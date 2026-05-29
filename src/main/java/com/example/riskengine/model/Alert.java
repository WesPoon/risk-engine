package com.example.riskengine.model;

import java.time.Instant;

/**
 * Alert payload dispatched to every AlertSender when a limit is breached.
 */
public record Alert(
        AggKey dimension,
        String bucketValue,
        double netDelta,
        double limitDelta,
        double netVega,
        double limitVega,
        Instant triggeredAt
) {
    @Override
    public String toString() {
        return ("[ALERT] %s/%s | netDelta=%.2f (limit %.2f) | netVega=%.2f (limit %.2f) | %s")
                .formatted(dimension, bucketValue,
                        netDelta, limitDelta,
                        netVega, limitVega,
                        triggeredAt);
    }
}
