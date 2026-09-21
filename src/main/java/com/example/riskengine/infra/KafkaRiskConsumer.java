package com.example.riskengine.infra;

import com.example.riskengine.core.RiskEngine;
import com.example.riskengine.model.PositionUpdate;
import com.example.riskengine.model.RiskEvent;
import com.example.riskengine.model.SpotUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls Kafka topics "positions" and "spots", deserializes each record into
 * a RiskEvent, and feeds it into RiskEngine via onUpdate(). Flushes the
 * engine batch after every BATCH_SIZE records or when a poll returns empty.
 */
public class KafkaRiskConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaRiskConsumer.class);

    private static final String TOPIC_POSITIONS = "positions";
    private static final String TOPIC_SPOTS     = "spots";
    private static final int    BATCH_SIZE      = 10;
    private static final Duration POLL_TIMEOUT  = Duration.ofMillis(500);

    private final RiskEngine   engine;
    private final KafkaConsumer<String, String> consumer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread pollThread;

    public KafkaRiskConsumer(RiskEngine engine, Properties kafkaProps) {
        this.engine   = engine;
        this.consumer = new KafkaConsumer<>(kafkaProps);
    }

    /**
     * Subscribes to the positions and spots topics and notifies the engine
     * via onSubscribe() for each topic. Must be called before start().
     */
    public void subscribe() {
        List<String> topics = List.of(TOPIC_POSITIONS, TOPIC_SPOTS);
        consumer.subscribe(topics);
        topics.forEach(engine::onSubscribe);
        log.info("KafkaRiskConsumer subscribed to topics: {}", topics);
    }

    /**
     * Starts the poll loop on a daemon background thread.
     */
    public void start() {
        running.set(true);
        pollThread = new Thread(this::pollLoop, "kafka-risk-consumer");
        pollThread.setDaemon(true);
        pollThread.start();
        log.info("KafkaRiskConsumer poll thread started");
    }

    /**
     * Signals the poll loop to stop and waits for the thread to exit.
     */
    public void stop() {
        running.set(false);
        consumer.wakeup();
        if (pollThread != null) {
            try {
                pollThread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        consumer.close();
        log.info("KafkaRiskConsumer stopped");
    }

    private void pollLoop() {
        int pendingCount = 0;
        try {
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);

                if (records.isEmpty()) {
                    // Drain any partially-filled batch on idle poll, then commit
                    if (pendingCount > 0) {
                        engine.flushBatch();
                        consumer.commitSync();
                        pendingCount = 0;
                    }
                    continue;
                }

                for (ConsumerRecord<String, String> record : records) {
                    RiskEvent event = deserialize(record);
                    if (event == null) continue;

                    engine.onUpdate(event);
                    pendingCount++;

                    // RiskEngine auto-flushes at BATCH_SIZE internally; commit offsets
                    // here so we only mark records consumed after the engine persisted them.
                    if (pendingCount >= BATCH_SIZE) {
                        consumer.commitSync();
                        pendingCount = 0;
                    }
                }
            }
        } catch (org.apache.kafka.common.errors.WakeupException e) {
            // expected on stop()
        } catch (Exception e) {
            log.error("Unexpected error in Kafka poll loop", e);
        } finally {
            if (pendingCount > 0) {
                engine.flushBatch();
            }
        }
    }

    private RiskEvent deserialize(ConsumerRecord<String, String> record) {
        try {
            if (TOPIC_POSITIONS.equals(record.topic())) {
                PositionUpdate pu = mapper.readValue(record.value(), PositionUpdate.class);
                return RiskEvent.ofPosition(pu);
            } else if (TOPIC_SPOTS.equals(record.topic())) {
                SpotUpdate su = mapper.readValue(record.value(), SpotUpdate.class);
                return RiskEvent.ofSpot(su);
            } else {
                log.warn("Unknown topic '{}', skipping record", record.topic());
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to deserialize record from topic '{}': {}",
                    record.topic(), record.value(), e);
            return null;
        }
    }
}
