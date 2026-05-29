package com.example.riskengine.model;

/**
 * Dimensions by which risk is aggregated. Each value maps to a field on Trade.
 */
public enum AggKey {
    GICS,       // GICS sector code
    COUNTRY,    // ISO country of the underlying
    PORTFOLIO   // portfolio / book name
}
