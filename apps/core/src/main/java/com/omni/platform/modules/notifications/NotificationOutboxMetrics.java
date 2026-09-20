package com.omni.platform.modules.notifications;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage.Status;
import com.omni.platform.modules.notifications.repositories.NotificationOutboxRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class NotificationOutboxMetrics {

    private final NotificationOutboxRepository repository;
    private final MeterRegistry registry;
    private final Counter sent;
    private final Counter retried;
    private final Counter dead;
    private final Counter rateLimited;
    private final Timer deliveryDuration;

    public NotificationOutboxMetrics(NotificationOutboxRepository repository, MeterRegistry registry) {
        this.repository = repository;
        this.registry = registry;
        this.sent = registry.counter("notification.outbox.sent");
        this.retried = registry.counter("notification.outbox.retry");
        this.dead = registry.counter("notification.outbox.dead");
        this.rateLimited = registry.counter("notification.delivery.rate.limited");
        this.deliveryDuration = registry.timer("notification.delivery.duration");
        Gauge.builder("notification.outbox.pending", repository,
                value -> value.countByStatus(Status.PENDING)).register(registry);
        Gauge.builder("notification.outbox.dead.current", repository,
                value -> value.countByStatus(Status.DEAD)).register(registry);
        Gauge.builder("notification.outbox.oldest.pending.age", repository,
                value -> value.findOldestPendingCreatedAt()
                        .map(createdAt -> Math.max(0, Duration.between(createdAt, Instant.now()).toSeconds()))
                        .orElse(0L)).baseUnit("seconds").register(registry);
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void delivered(Timer.Sample sample) {
        sent.increment();
        sample.stop(deliveryDuration);
    }

    public void retried(Timer.Sample sample) {
        retried.increment();
        sample.stop(deliveryDuration);
    }

    public void dead(Timer.Sample sample) {
        dead.increment();
        sample.stop(deliveryDuration);
    }

    public void rateLimited(Timer.Sample sample) {
        rateLimited.increment();
        retried(sample);
    }
}
