package com.example.riskengine.infra;

import com.example.riskengine.AlertSender;
import com.example.riskengine.model.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simulated e-mail sender – prints to log in a real deployment this would
 * delegate to JavaMail / SendGrid.
 */
public class EmailSender implements AlertSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);
    private final String recipient;

    public EmailSender(String recipient) {
        this.recipient = recipient;
    }

    @Override
    public void sendAlert(Alert alert) {
        log.warn("[EMAIL → {}] {}", recipient, alert);
    }
}
