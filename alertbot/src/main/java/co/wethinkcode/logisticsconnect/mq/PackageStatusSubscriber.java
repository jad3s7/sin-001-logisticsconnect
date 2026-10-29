package co.wethinkcode.logisticsconnect.mq;

import co.wethinkcode.logisticsconnect.JsonParser;
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

/** Consumes delay-stage changes and reports parsed messages to AlertBot. */
public class PackageStatusSubscriber implements AutoCloseable, MessageListener {

    private static final Logger LOG = Logger.getLogger(PackageStatusSubscriber.class.getName());

    public record MessageData(String hubId, int stage, Instant timestamp) {}

    private final BiConsumer<MessageData, Boolean> onMessage;
    private final Connection connection;
    private final Session session;
    private final MessageConsumer consumer;

    public PackageStatusSubscriber(BiConsumer<MessageData, Boolean> onMessage) throws JMSException {
        this(onMessage, MqConfig.BROKER_URL, MqConfig.TOPIC);
    }

    public PackageStatusSubscriber(BiConsumer<MessageData, Boolean> onMessage,
                                   String brokerUrl, String topic) throws JMSException {
        this.onMessage = onMessage;
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setUserName(MqConfig.USER);
        factory.setPassword(MqConfig.PASSWORD);
        this.connection = factory.createConnection();
        this.connection.start();
        this.session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        this.consumer = session.createConsumer(session.createTopic(topic));
        this.consumer.setMessageListener(this);
        LOG.info(() -> "AlertBot SUBSCRIBED to " + topic + " at " + brokerUrl);
    }

    @Override
    public void onMessage(Message message) {
        if (!(message instanceof TextMessage text)) return;
        try {
            MessageData data = parse(text.getText());
            onMessage.accept(data, data != null);
        } catch (Exception e) {
            onMessage.accept(null, false);
            LOG.warning("Bad message: " + e.getMessage());
        }
    }

    static MessageData parse(String payload) {
        Map<String, Object> object = JsonParser.parseObject(payload);
        Object hubValue = object.get("hubId");
        Object stageValue = object.get("stage");
        Object timestampValue = object.get("timestamp");
        if (!(hubValue instanceof String hub) || hub.isBlank()) return null;
        if (!(stageValue instanceof Number stage) || stage.intValue() < 0 || stage.intValue() > 8) return null;
        Instant timestamp = timestampValue instanceof String s ? Instant.parse(s) : Instant.now();
        return new MessageData(hub.trim(), stage.intValue(), timestamp);
    }

    @Override
    public void close() {
        try { consumer.close(); } catch (JMSException ignored) { }
        try { session.close(); } catch (JMSException ignored) { }
        try { connection.close(); } catch (JMSException ignored) { }
    }
}
