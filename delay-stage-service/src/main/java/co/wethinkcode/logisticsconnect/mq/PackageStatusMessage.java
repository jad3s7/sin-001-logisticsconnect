package co.wethinkcode.logisticsconnect.mq;

import co.wethinkcode.logisticsconnect.Json;

import java.time.Instant;

/** Single source of truth for the message payload shape on package-status-topic. */
public final class PackageStatusMessage {

    public static String toJson(String hubId, int stage, Instant timestamp) {
        return "{\"hubId\":\"" + Json.escape(hubId) + "\","
                + "\"stage\":"    + stage                + ","
                + "\"timestamp\":\"" + timestamp        + "\"}";
    }

    private PackageStatusMessage() {}
}