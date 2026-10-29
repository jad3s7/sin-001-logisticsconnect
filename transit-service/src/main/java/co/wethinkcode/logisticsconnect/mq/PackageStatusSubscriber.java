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
import java.util.logging.Logger;

public class PackageStatusSubscriber implements AutoCloseable, MessageListener {

    private static final Logger LOG = Logger.getLogger(PackageStatusSubscriber.class.getName());

    private final StageCache cache;
    private final MqStats stats;
    private final Connection connection;
    private final Session session;
    private final MessageConsumer consumer;

    public PackageStatusSubscriber(StageCache cache, MqStats stats) throws JMSException {
        this(cache, stats, MqConfig.BROKER_URL, MqConfig.TOPIC);
    }

    public PackageStatusSubscriber(StageCache cache, MqStats stats, String brokerUrl, String topic)
            throws JMSException {
        this.cache = cache;
        this.stats = stats;

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setUserName(MqConfig.USER);
        factory.setPassword(MqConfig.PASSWORD);
        this.connection = factory.createConnection();
        this.connection.start();
        this.session    = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        this.consumer   = session.createConsumer(session.createTopic(topic));
        this.consumer.setMessageListener(this);

        stats.markConnected(brokerUrl, topic);
        LOG.info(() -> "SUBSCRIBED to " + topic + " at " + brokerUrl
                + " — no further synchronous calls to delay-stage-service");
    }

    @Override
    public void onMessage(Message message) {
        if (!(message instanceof TextMessage text)) { stats.recordRejected(); return; }
        try {
            applyPayload(text.getText(), cache, stats);
        } catch (Exception e) {
            stats.recordRejected();
            LOG.warning("Rejected message: " + e.getMessage());
        }
    }

    /**
     * Static so tests can drive it directly without a live broker, and so the
     * payload contract is validated in one place.
     */
    static void applyPayload(String payload, StageCache cache, MqStats stats) {
        Map<String, Object> obj = JsonParser.parseObject(payload);
        Object hubIdO = obj.get("hubId");
        Object stageO = obj.get("stage");
        Object tsO    = obj.get("timestamp");

        if (!(hubIdO instanceof String hubId) || hubId.isBlank()) {
            throw new IllegalArgumentException("payload missing hubId");
        }
        if (!(stageO instanceof Number stageN)) {
            throw new IllegalArgumentException("payload missing numeric stage");
        }
        int stage = stageN.intValue();
        if (stage < 0 || stage > 8) {
            throw new IllegalArgumentException("stage out of range: " + stage);
        }
        Instant ts = (tsO instanceof String s) ? Instant.parse(s) : Instant.now();

        cache.update(hubId, stage, ts);
        stats.recordReceived();
        LOG.info(() -> "Received from " + MqConfig.TOPIC + ": " + hubId
                + " stage=" + stage + " ts=" + ts);
    }

    @Override
    public void close() {
        try { consumer.close();  } catch (JMSException ignored) { }
        try { session.close();   } catch (JMSException ignored) { }
        try { connection.close();} catch (JMSException ignored) { }
    }
}