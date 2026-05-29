package com.example.riskengine.model;

/**
 * Unified wrapper delivered to RiskObserver.onUpdate().
 * Exactly one of positionUpdate / spotUpdate is non-null.
 */
public record RiskEvent(
        RiskEventType type,
        PositionUpdate positionUpdate,
        SpotUpdate spotUpdate
) {
    public enum RiskEventType { POSITION_UPDATE, SPOT_UPDATE }

    public static RiskEvent ofPosition(PositionUpdate p) {
        return new RiskEvent(RiskEventType.POSITION_UPDATE, p, null);
    }

    public static RiskEvent ofSpot(SpotUpdate s) {
        return new RiskEvent(RiskEventType.SPOT_UPDATE, null, s);
    }
}
