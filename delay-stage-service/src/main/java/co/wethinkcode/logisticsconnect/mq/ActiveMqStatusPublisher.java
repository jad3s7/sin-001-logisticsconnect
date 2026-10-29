package co.wethinkcode.logisticsconnect.mq;

import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.time.Instant;
import java.util.logging.Logger;

public class ActiveMqStatusPublisher implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ActiveMqStatusPublisher.class.getName());

    private final Connection connection;
    private final Session session;
    private final MessageProducer producer;

    public ActiveMqStatusPublisher() throws JMSException {
        this(MqConfig.BROKER_URL, MqConfig.TOPIC);
    }

    public ActiveMqStatusPublisher(String brokerUrl, String topic) throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setUserName(MqConfig.USER);
        factory.setPassword(MqConfig.PASSWORD);
        this.connection = factory.createConnection();
        this.connection.start();
        this.session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        this.producer = session.createProducer(session.createTopic(topic));
        LOG.info(() -> "Connected to broker " + brokerUrl + " as producer on " + topic);
    }

    public void publish(String hubId, int stage, Instant timestamp) throws JMSException {
        String payload = PackageStatusMessage.toJson(hubId, stage, timestamp);
        TextMessage msg = session.createTextMessage(payload);
        producer.send(msg);
        LOG.info(() -> "Published: " + payload);
    }

    @Override
    public void close() {
        try { producer.close();  } catch (JMSException ignored) { }
        try { session.close();   } catch (JMSException ignored) { }
        try { connection.close();} catch (JMSException ignored) { }
    }
}