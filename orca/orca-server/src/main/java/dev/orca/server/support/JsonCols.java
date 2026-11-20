package dev.orca.server.support;

import dev.orca.protocol.Json;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;

/** Helpers for the JSON text columns. */
public final class JsonCols {
  private JsonCols() {}

  public static String write(Object v) {
    return v == null ? null : Json.write(v);
  }

  public static Map<String, Object> map(String json) {
    return json == null || json.isBlank() ? new java.util.LinkedHashMap<>() : Json.readMap(json);
  }

  public static Object any(String json) {
    return json == null || json.isBlank() ? null : Json.MAPPER.readValue(json, Object.class);
  }

  public static List<String> strings(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    return Json.MAPPER.readValue(json, new TypeReference<List<String>>() {});
  }

  public static <T> T read(String json, Class<T> type) {
    return json == null || json.isBlank() ? null : Json.read(json, type);
  }
}
