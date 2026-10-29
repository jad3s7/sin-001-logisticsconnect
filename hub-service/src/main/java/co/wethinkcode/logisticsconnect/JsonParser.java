package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Small shared-shape JSON parsing helper for this independent Maven module. */
final class JsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> ARRAY_TYPE = new TypeReference<>() {};

    private JsonParser() {}

    static Object parse(String json) throws IOException {
        return MAPPER.readValue(json, Object.class);
    }

    static Map<String, Object> parseObject(String json) throws IOException {
        return MAPPER.readValue(json, OBJECT_TYPE);
    }

    static List<Object> parseArray(String json) throws IOException {
        return MAPPER.readValue(json, ARRAY_TYPE);
    }
}
