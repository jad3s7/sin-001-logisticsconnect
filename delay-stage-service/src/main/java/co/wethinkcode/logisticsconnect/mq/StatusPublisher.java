package co.wethinkcode.logisticsconnect.mq;

import java.time.Instant;

/** Publishes a hub's delay-stage update to the configured status topic. */
@FunctionalInterface
public interface StatusPublisher {
    void publish(String hubId, int stage, Instant timestamp) throws Exception;
}
