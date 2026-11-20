package dev.orca.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The JSON mapper shared by the daemon, the agent drivers and the server's wire layer. */
public final class Json {

  public static final JsonMapper MAPPER = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
      .changeDefaultPropertyInclusion(i -> i.withValueInclusion(JsonInclude.Include.NON_NULL))
      .build();

  private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

  private Json() {}

  public static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  public static <T> T read(String json, Class<T> type) {
    return MAPPER.readValue(json, type);
  }

  public static Map<String, Object> readMap(String json) {
    return MAPPER.readValue(json, MAP);
  }

  public static Map<String, Object> toMap(Object value) {
    return value == null ? new LinkedHashMap<>() : MAPPER.convertValue(value, MAP);
  }

  public static <T> T convert(Object value, Class<T> type) {
    return MAPPER.convertValue(value, type);
  }

  // ---- tolerant accessors for loosely typed protocol payloads

  public static String str(Object map, String key) {
    Object v = get(map, key);
    return v == null ? null : String.valueOf(v);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> obj(Object map, String key) {
    Object v = get(map, key);
    return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  @SuppressWarnings("unchecked")
  public static List<Object> list(Object map, String key) {
    Object v = get(map, key);
    return v instanceof List<?> l ? (List<Object>) l : List.of();
  }

  public static long num(Object map, String key, long fallback) {
    Object v = get(map, key);
    return v instanceof Number n ? n.longValue() : fallback;
  }

  public static boolean bool(Object map, String key) {
    return Boolean.TRUE.equals(get(map, key));
  }

  /** Follows a dotted path through nested maps. */
  public static Object get(Object map, String path) {
    Object cur = map;
    for (String part : path.split("\\.")) {
      if (!(cur instanceof Map<?, ?> m)) {
        return null;
      }
      cur = m.get(part);
    }
    return cur;
  }
}
