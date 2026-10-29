package co.wethinkcode.logisticsconnect.mq;

import co.wethinkcode.logisticsconnect.JsonParser;
import co.wethinkcode.logisticsconnect.MqStats;
import co.wethinkcode.logisticsconnect.StageCache;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.time.Instant;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Subscribes to package-status-topic and forwards each decoded message to a
 * callback. No stage state is kept here; policy (thresholding, dedup) lives in
 * the caller. Same class shape as transit-service's subscriber for symmetry.
 */
public class PackageStatusSubscriber implements AutoCloseable, MessageListener {

    private static final Logger LOG = Logger.getLogger(PackageStatusSubscriber.class.getName());

    public record MessageData(String hubId, int stage, Instant timestamp) {}

    private final BiConsumer<String, Integer> onStageChange;   // (hubId, stage)
    private final java.util.function.BiConsumer<MessageData, Boolean> onMessage;
    private final Connection connection;
    private final Session session;
    private final MessageConsumer consumer;

    public PackageStatusSubscriber(StageCache cache, MqStats stats) throws JMSException {
        this((data, valid) -> {
            if (valid && data != null) {
                cache.update(data.hubId(), data.stage(), data.timestamp());
                stats.recordReceived();
            } else {
                stats.recordRejected();
            }
        }, MqConfig.BROKER_URL, MqConfig.TOPIC);
        stats.markConnected(MqConfig.BROKER_URL, MqConfig.TOPIC);
    }

    public PackageStatusSubscriber(BiConsumer<MessageData, Boolean> onMessage) throws JMSException {
        this(onMessage, MqConfig.BROKER_URL, MqConfig.TOPIC);
    }

    public PackageStatusSubscriber(BiConsumer<MessageData, Boolean> onMessage,
                                   String brokerUrl, String topic) throws JMSException {
        // Accept the simpler signature used by tests; this field is only there for symmetry.
        this.onMessage   = onMessage;
        this.onStageChange = (id, s) -> { };

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setUserName(MqConfig.USER);
        factory.setPassword(MqConfig.PASSWORD);
        this.connection = factory.createConnection();
        this.connection.start();
        this.session    = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        this.consumer   = session.createConsumer(session.createTopic(topic));
        this.consumer.setMessageListener(this);

        LOG.info(() -> "AlertBot SUBSCRIBED to " + topic + " at " + brokerUrl);
    }

    @Override
    public void onMessage(Message message) {
        if (!(message instanceof TextMessage text)) return;
        try {
            MessageData data = parse(text.getText());
            if (data == null) { onMessage.accept(null, false); return; }
            onMessage.accept(data, true);
        } catch (Exception e) {
            onMessage.accept(null, false);
            LOG.warning("Bad message: " + e.getMessage());
        }
    }

    static MessageData parse(String payload) {
        Map<String, Object> obj = JsonParser.parseObject(payload);
        Object hubO = obj.get("hubId");
        Object stO  = obj.get("stage");
        Object tsO  = obj.get("timestamp");
        if (!(hubO instanceof String hub) || hub.isBlank()) return null;
        if (!(stO instanceof Number st)) return null;
        Instant ts = (tsO instanceof String s) ? Instant.parse(s) : Instant.now();
        return new MessageData(hub, st.intValue(), ts);
    }

    @Override
    public void close() {
        try { consumer.close();  } catch (JMSException ignored) { }
        try { session.close();   } catch (JMSException ignored) { }
        try { connection.close();} catch (JMSException ignored) { }
    }
}
