package com.example.riskengine.infra;

import com.example.riskengine.AlertSender;
import com.example.riskengine.model.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simulated Symphony chat-room sender.
 * In production this would call the Symphony REST API.
 */
public class SymphonySender implements AlertSender {

    private static final Logger log = LoggerFactory.getLogger(SymphonySender.class);
    private final String roomId;

    public SymphonySender(String roomId) {
        this.roomId = roomId;
    }

    @Override
    public void sendAlert(Alert alert) {
        log.warn("[SYMPHONY → room:{}] {}", roomId, alert);
    }
}
