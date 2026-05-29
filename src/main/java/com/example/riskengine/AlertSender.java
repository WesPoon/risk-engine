package com.example.riskengine;

import com.example.riskengine.model.Alert;

/** Strategy for dispatching breach alerts. */
public interface AlertSender {
    void sendAlert(Alert alert);
}
