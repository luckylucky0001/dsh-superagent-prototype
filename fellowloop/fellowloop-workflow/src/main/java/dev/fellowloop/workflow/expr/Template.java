package dev.fellowloop.workflow.expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders {@code {{ expression }}} placeholders. A string that is exactly one placeholder keeps the
 * value's type, so {@code "{{ nodes.a.output }}"} yields a map; anything else renders to a string.
 */
public final class Template {

  private static final String OPEN = "{{";
  private static final String CLOSE = "}}";

  private Template() {}

  public static boolean hasPlaceholders(String text) {
    return text != null && text.contains(OPEN);
  }

  public static Object render(String text, Map<String, ?> vars) {
    if (!hasPlaceholders(text)) {
      return text;
    }
    String trimmed = text.trim();
    if (trimmed.startsWith(OPEN) && trimmed.endsWith(CLOSE)
        && trimmed.indexOf(OPEN, 2) < 0 && trimmed.indexOf(CLOSE) == trimmed.length() - 2) {
      return Expression.eval(trimmed.substring(2, trimmed.length() - 2), vars);
    }
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (true) {
      int start = text.indexOf(OPEN, i);
      if (start < 0) {
        out.append(text, i, text.length());
        return out.toString();
      }
      int end = text.indexOf(CLOSE, start + 2);
      if (end < 0) {
        throw new ExpressionException("unclosed '{{' in template");
      }
      out.append(text, i, start);
      out.append(Values.stringify(Expression.eval(text.substring(start + 2, end), vars)));
      i = end + 2;
    }
  }

  public static String renderString(String text, Map<String, ?> vars) {
    return Values.stringify(render(text, vars));
  }

  /** Resolves templates in every string of a nested config structure. */
  @SuppressWarnings("unchecked")
  public static Object resolve(Object value, Map<String, ?> vars) {
    if (value instanceof String s) {
      return render(s, vars);
    }
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      ((Map<String, Object>) m).forEach((k, v) -> out.put(k, resolve(v, vars)));
      return out;
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object o : list) {
        out.add(resolve(o, vars));
      }
      return out;
    }
    return value;
  }

  /** Throws {@link ExpressionException} if any placeholder does not parse. */
  public static void check(String text) {
    if (!hasPlaceholders(text)) {
      return;
    }
    int i = 0;
    while (true) {
      int start = text.indexOf(OPEN, i);
      if (start < 0) {
        return;
      }
      int end = text.indexOf(CLOSE, start + 2);
      if (end < 0) {
        throw new ExpressionException("unclosed '{{' in template");
      }
      Expression.parse(text.substring(start + 2, end));
      i = end + 2;
    }
  }
}
