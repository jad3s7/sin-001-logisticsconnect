package co.wethinkcode.logisticsconnect.mq;

import co.wethinkcode.logisticsconnect.JsonParser;
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
    private final Connection connection;
    private final Session session;
    private final MessageConsumer consumer;

    public PackageStatusSubscriber(StageCache cache) throws JMSException {
        this(cache, MqConfig.BROKER_URL, MqConfig.TOPIC);
    }

    public PackageStatusSubscriber(StageCache cache, String brokerUrl, String topic) throws JMSException {
        this.cache = cache;
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setUserName(MqConfig.USER);
        factory.setPassword(MqConfig.PASSWORD);
        this.connection = factory.createConnection();
        this.connection.start();
        this.session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        this.consumer = session.createConsumer(session.createTopic(topic));
        this.consumer.setMessageListener(this);
        LOG.info(() -> "Subscribed to " + brokerUrl + " topic " + topic);
    }

    @Override
    public void onMessage(Message message) {
        if (!(message instanceof TextMessage text)) return;
        try {
            onPayload(text.getText());
        } catch (Exception e) {
            LOG.warning("Bad message on package-status-topic: " + e.getMessage());
        }
    }

    /** Extracted so tests can drive the cache without a real broker. */
    void onPayload(String payload) {
        Map<String, Object> obj = JsonParser.parseObject(payload);
        Object hubIdO = obj.get("hubId");
        Object stageO = obj.get("stage");
        Object tsO    = obj.get("timestamp");
        if (!(hubIdO instanceof String hubId)) { LOG.warning("message missing hubId"); return; }
        if (!(stageO instanceof Number stage)) { LOG.warning("message missing numeric stage"); return; }
        Instant ts = (tsO instanceof String s) ? Instant.parse(s) : Instant.now();
        cache.update(hubId, stage.intValue(), ts);
        LOG.info(() -> "Received status: " + hubId + " stage=" + stage + " ts=" + ts);
    }

    @Override
    public void close() {
        try { consumer.close();  } catch (JMSException ignored) { }
        try { session.close();   } catch (JMSException ignored) { }
        try { connection.close();} catch (JMSException ignored) { }
    }
}