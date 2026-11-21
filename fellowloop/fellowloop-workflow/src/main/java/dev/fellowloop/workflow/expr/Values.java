package dev.fellowloop.workflow.expr;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/** Value semantics shared by expressions and templates: truthiness, equality and string forms. */
public final class Values {

  private Values() {}

  public static boolean truthy(Object v) {
    if (v == null) {
      return false;
    }
    if (v instanceof Boolean b) {
      return b;
    }
    if (v instanceof Number n) {
      return n.doubleValue() != 0;
    }
    if (v instanceof CharSequence s) {
      return !s.isEmpty() && !"false".contentEquals(s);
    }
    if (v instanceof Collection<?> c) {
      return !c.isEmpty();
    }
    if (v instanceof Map<?, ?> m) {
      return !m.isEmpty();
    }
    return true;
  }

  public static boolean equal(Object a, Object b) {
    if (a == null || b == null) {
      return a == b;
    }
    if (a instanceof Number x && b instanceof Number y) {
      return x.doubleValue() == y.doubleValue();
    }
    if (a instanceof Number || b instanceof Number) {
      Double x = toNumber(a);
      Double y = toNumber(b);
      return x != null && x.equals(y);
    }
    if (a instanceof Boolean || b instanceof Boolean) {
      return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
    }
    return a.equals(b) || stringify(a).equals(stringify(b));
  }

  public static Double toNumber(Object v) {
    if (v instanceof Number n) {
      return n.doubleValue();
    }
    if (v instanceof CharSequence s) {
      try {
        return Double.parseDouble(s.toString().trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    if (v instanceof Boolean b) {
      return b ? 1.0 : 0.0;
    }
    return null;
  }

  /** Strings stay as they are; maps and lists become JSON; whole doubles drop their fraction. */
  public static String stringify(Object v) {
    if (v == null) {
      return "";
    }
    if (v instanceof CharSequence s) {
      return s.toString();
    }
    if (v instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
      return Long.toString(d.longValue());
    }
    if (v instanceof Map<?, ?> || v instanceof Collection<?>) {
      return json(v);
    }
    return String.valueOf(v);
  }

  public static String json(Object v) {
    StringBuilder sb = new StringBuilder();
    writeJson(sb, v);
    return sb.toString();
  }

  private static void writeJson(StringBuilder sb, Object v) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof Boolean || v instanceof Number) {
      sb.append(v instanceof Double d && d == Math.rint(d) && !d.isInfinite() ? Long.toString(d.longValue()) : v);
    } else if (v instanceof Map<?, ?> m) {
      sb.append('{');
      Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<?, ?> e = it.next();
        quote(sb, String.valueOf(e.getKey()));
        sb.append(':');
        writeJson(sb, e.getValue());
        if (it.hasNext()) {
          sb.append(',');
        }
      }
      sb.append('}');
    } else if (v instanceof Collection<?> c) {
      sb.append('[');
      Iterator<?> it = c.iterator();
      while (it.hasNext()) {
        writeJson(sb, it.next());
        if (it.hasNext()) {
          sb.append(',');
        }
      }
      sb.append(']');
    } else {
      quote(sb, String.valueOf(v));
    }
  }

  private static void quote(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
