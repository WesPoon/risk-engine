package com.example.riskengine;

import com.example.riskengine.model.RiskEvent;

/**
 * Observer notified for each inbound market event.
 */
public interface RiskObserver {
    void onSubscribe(String topic);
    void onUpdate(RiskEvent event);
}
