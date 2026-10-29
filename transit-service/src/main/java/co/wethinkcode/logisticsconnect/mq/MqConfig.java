package co.wethinkcode.logisticsconnect.mq;

/**
 * Shared by every producer/consumer service that talks to the "package-status-topic"
 * ActiveMQ topic. Duplicated into each participating service's own source tree,
 * since these are independent Maven projects with no shared parent pom.
 */
public final class MqConfig {

    public static final String BROKER_URL = "tcp://localhost:61616";
    public static final String TOPIC = "package-status-topic";
    public static final String USER = env("MQ_USER", "admin");
    public static final String PASSWORD = env("MQ_PASSWORD", "admin");

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private MqConfig() {
    }
}
