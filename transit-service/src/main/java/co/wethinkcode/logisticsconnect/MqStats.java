package co.wethinkcode.logisticsconnect;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Observable stats for the package-status-topic subscription. */
public class MqStats {

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile Instant lastMessageAt;
    private volatile boolean connected;
    private volatile String brokerUrl = "";
    private volatile String topic = "";

    public void markConnected(String brokerUrl, String topic) {
        this.brokerUrl = brokerUrl;
        this.topic = topic;
        this.connected = true;
    }

    public void recordReceived() {
        received.incrementAndGet();
        lastMessageAt = Instant.now();
    }

    public void recordRejected() {
        rejected.incrementAndGet();
    }

    public long messagesReceived() { return received.get(); }
    public long messagesRejected() { return rejected.get(); }

    public String toJson(int hubsKnown) {
        return "{"
                + "\"connected\":"        + connected                                           + ","
                + "\"brokerUrl\":\""      + Json.escape(brokerUrl)                             + "\","
                + "\"topic\":\""          + Json.escape(topic)                                 + "\","
                + "\"messagesReceived\":" + received.get()                                     + ","
                + "\"messagesRejected\":" + rejected.get()                                     + ","
                + "\"lastMessageAt\":"    + (lastMessageAt == null ? "null" : "\"" + lastMessageAt + "\"") + ","
                + "\"hubsKnown\":"        + hubsKnown
                + "}";
    }
}