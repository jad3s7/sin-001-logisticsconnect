package co.wethinkcode.logisticsconnect.mq;

/** Shared MQ config. Duplicated into each service per the brief's pattern. */
public final class MqConfig {

    public static final String BROKER_URL = env("MQ_BROKER_URL", "tcp://localhost:61616");
    public static final String TOPIC      = env("MQ_TOPIC",      "package-status-topic");
    public static final String USER       = env("MQ_USER",       "admin");
    public static final String PASSWORD   = env("MQ_PASSWORD",   "admin");

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private MqConfig() {}
}