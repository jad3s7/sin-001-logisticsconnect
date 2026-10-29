package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Map;

/** Small shared-shape JSON parsing helper for this independent Maven module. */
public final class JsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> ARRAY_TYPE = new TypeReference<>() {};

    private JsonParser() {}

    public static Object parse(String json) {
        try { return MAPPER.readValue(json, Object.class); }
        catch (JsonProcessingException e) { throw invalidJson(e); }
    }

    public static Map<String, Object> parseObject(String json) {
        try { return MAPPER.readValue(json, OBJECT_TYPE); }
        catch (JsonProcessingException e) { throw invalidJson(e); }
    }

    public static List<Object> parseArray(String json) {
        try { return MAPPER.readValue(json, ARRAY_TYPE); }
        catch (JsonProcessingException e) { throw invalidJson(e); }
    }

    private static IllegalArgumentException invalidJson(JsonProcessingException cause) {
        return new IllegalArgumentException("Invalid JSON", cause);
    }
}
