package dev.orca.workflow.expr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small, side-effect free expression language for edge conditions and templates.
 *
 * <pre>
 *   nodes.review.output.verdict == 'pass' && !empty(trigger.issue.labels)
 *   contains(lower(trigger.comment.body), '@bot') || len(nodes.plan.output.steps) > 3
 * </pre>
 *
 * Supports literals, dotted and indexed paths into maps and lists, {@code ! - + * / %}, comparisons,
 * {@code && ||} with short-circuit, the ternary {@code a ? b : c} and a fixed set of functions. There
 * is no way to call Java methods, construct objects or reach anything outside the variables map.
 */
public final class Expression {

  private final String source;
  private final Node root;

  private Expression(String source, Node root) {
    this.source = source;
    this.root = root;
  }

  public static Expression parse(String source) {
    Parser p = new Parser(source);
    Node n = p.parseTernary();
    p.expectEnd();
    return new Expression(source, n);
  }

  public static Object eval(String source, Map<String, ?> vars) {
    return parse(source).evaluate(vars);
  }

  public Object evaluate(Map<String, ?> vars) {
    return root.eval(vars);
  }

  public boolean test(Map<String, ?> vars) {
    return Values.truthy(evaluate(vars));
  }

  @Override
  public String toString() {
    return source;
  }

  // ---------------------------------------------------------------- AST

  private interface Node {
    Object eval(Map<String, ?> vars);
  }

  private record Literal(Object value) implements Node {
    public Object eval(Map<String, ?> vars) {
      return value;
    }
  }

  private record Var(String name) implements Node {
    public Object eval(Map<String, ?> vars) {
      return vars.get(name);
    }
  }

  private record Member(Node target, Node key) implements Node {
    public Object eval(Map<String, ?> vars) {
      return index(target.eval(vars), key.eval(vars));
    }
  }

  private record ListLit(List<Node> items) implements Node {
    public Object eval(Map<String, ?> vars) {
      List<Object> out = new ArrayList<>(items.size());
      for (Node n : items) {
        out.add(n.eval(vars));
      }
      return out;
    }
  }

  private record Unary(String op, Node operand) implements Node {
    public Object eval(Map<String, ?> vars) {
      Object v = operand.eval(vars);
      return switch (op) {
        case "!" -> !Values.truthy(v);
        case "-" -> {
          Double d = Values.toNumber(v);
          if (d == null) {
            throw new ExpressionException("cannot negate " + Values.stringify(v));
          }
          yield -d;
        }
        default -> throw new IllegalStateException(op);
      };
    }
  }

  private record Binary(String op, Node left, Node right) implements Node {
    public Object eval(Map<String, ?> vars) {
      if (op.equals("&&")) {
        Object l = left.eval(vars);
        return Values.truthy(l) ? right.eval(vars) : l;
      }
      if (op.equals("||")) {
        Object l = left.eval(vars);
        return Values.truthy(l) ? l : right.eval(vars);
      }
      Object l = left.eval(vars);
      Object r = right.eval(vars);
      switch (op) {
        case "==":
          return Values.equal(l, r);
        case "!=":
          return !Values.equal(l, r);
        case "+":
          if (l instanceof Number && r instanceof Number) {
            return ((Number) l).doubleValue() + ((Number) r).doubleValue();
          }
          return Values.stringify(l) + Values.stringify(r);
        default:
          break;
      }
      if (op.equals("<") || op.equals("<=") || op.equals(">") || op.equals(">=")) {
        int c = compare(l, r);
        return switch (op) {
          case "<" -> c < 0;
          case "<=" -> c <= 0;
          case ">" -> c > 0;
          default -> c >= 0;
        };
      }
      Double a = Values.toNumber(l);
      Double b = Values.toNumber(r);
      if (a == null || b == null) {
        throw new ExpressionException("'" + op + "' needs numbers");
      }
      return switch (op) {
        case "-" -> a - b;
        case "*" -> a * b;
        case "/" -> {
          if (b == 0) {
            throw new ExpressionException("division by zero");
          }
          yield a / b;
        }
        case "%" -> a % b;
        default -> throw new IllegalStateException(op);
      };
    }
  }

  private record Ternary(Node cond, Node yes, Node no) implements Node {
    public Object eval(Map<String, ?> vars) {
      return Values.truthy(cond.eval(vars)) ? yes.eval(vars) : no.eval(vars);
    }
  }

  private record Call(String fn, List<Node> args) implements Node {
    public Object eval(Map<String, ?> vars) {
      List<Object> a = new ArrayList<>(args.size());
      for (Node n : args) {
        a.add(n.eval(vars));
      }
      return Functions.call(fn, a);
    }
  }

  static Object index(Object target, Object key) {
    if (target == null || key == null) {
      return null;
    }
    if (target instanceof Map<?, ?> m) {
      return m.get(Values.stringify(key));
    }
    if (target instanceof List<?> list) {
      Double d = Values.toNumber(key);
      if (d == null) {
        return "length".equals(key) ? list.size() : null;
      }
      int i = d.intValue();
      if (i < 0) {
        i += list.size();
      }
      return i >= 0 && i < list.size() ? list.get(i) : null;
    }
    if (target instanceof CharSequence s && "length".equals(key)) {
      return s.length();
    }
    return null;
  }

  private static int compare(Object l, Object r) {
    Double a = Values.toNumber(l);
    Double b = Values.toNumber(r);
    if (a != null && b != null) {
      return Double.compare(a, b);
    }
    return Values.stringify(l).compareTo(Values.stringify(r));
  }

  // ---------------------------------------------------------------- functions

  static final class Functions {
    static final List<String> NAMES =
        List.of("contains", "startsWith", "endsWith", "lower", "upper", "trim", "len", "empty",
            "default", "str", "num", "json", "join", "matchesAny");

    static Object call(String fn, List<Object> a) {
      return switch (fn) {
        case "contains" -> {
          arity(fn, a, 2);
          Object hay = a.get(0);
          if (hay instanceof Collection<?> c) {
            yield c.stream().anyMatch(x -> Values.equal(x, a.get(1)));
          }
          if (hay instanceof Map<?, ?> m) {
            yield m.containsKey(Values.stringify(a.get(1)));
          }
          yield Values.stringify(hay).contains(Values.stringify(a.get(1)));
        }
        case "startsWith" -> {
          arity(fn, a, 2);
          yield Values.stringify(a.get(0)).startsWith(Values.stringify(a.get(1)));
        }
        case "endsWith" -> {
          arity(fn, a, 2);
          yield Values.stringify(a.get(0)).endsWith(Values.stringify(a.get(1)));
        }
        case "lower" -> {
          arity(fn, a, 1);
          yield Values.stringify(a.get(0)).toLowerCase(Locale.ROOT);
        }
        case "upper" -> {
          arity(fn, a, 1);
          yield Values.stringify(a.get(0)).toUpperCase(Locale.ROOT);
        }
        case "trim" -> {
          arity(fn, a, 1);
          yield Values.stringify(a.get(0)).trim();
        }
        case "len" -> {
          arity(fn, a, 1);
          Object v = a.get(0);
          if (v instanceof Collection<?> c) {
            yield c.size();
          }
          if (v instanceof Map<?, ?> m) {
            yield m.size();
          }
          yield v == null ? 0 : Values.stringify(v).length();
        }
        case "empty" -> {
          arity(fn, a, 1);
          Object v = a.get(0);
          yield v == null
              || (v instanceof CharSequence s && s.toString().isBlank())
              || (v instanceof Collection<?> c && c.isEmpty())
              || (v instanceof Map<?, ?> m && m.isEmpty());
        }
        case "default" -> {
          arity(fn, a, 2);
          Object v = a.get(0);
          yield v == null || (v instanceof CharSequence s && s.isEmpty()) ? a.get(1) : v;
        }
        case "str" -> {
          arity(fn, a, 1);
          yield Values.stringify(a.get(0));
        }
        case "num" -> {
          arity(fn, a, 1);
          yield Values.toNumber(a.get(0));
        }
        case "json" -> {
          arity(fn, a, 1);
          yield Values.json(a.get(0));
        }
        case "join" -> {
          arity(fn, a, 2);
          if (!(a.get(0) instanceof Collection<?> c)) {
            yield Values.stringify(a.get(0));
          }
          List<String> parts = new ArrayList<>();
          for (Object o : c) {
            parts.add(Values.stringify(o));
          }
          yield String.join(Values.stringify(a.get(1)), parts);
        }
        case "matchesAny" -> {
          // matchesAny(text, ['a', 'b']): case-insensitive substring match against any needle
          arity(fn, a, 2);
          String text = Values.stringify(a.get(0)).toLowerCase(Locale.ROOT);
          if (!(a.get(1) instanceof Collection<?> c)) {
            yield text.contains(Values.stringify(a.get(1)).toLowerCase(Locale.ROOT));
          }
          yield c.stream().anyMatch(x -> text.contains(Values.stringify(x).toLowerCase(Locale.ROOT)));
        }
        default -> throw new ExpressionException("unknown function: " + fn);
      };
    }

    private static void arity(String fn, List<Object> a, int n) {
      if (a.size() != n) {
        throw new ExpressionException(fn + "() takes " + n + " argument(s)");
      }
    }
  }

  // ---------------------------------------------------------------- parser

  private static final class Parser {
    private final String s;
    private int pos;

    Parser(String s) {
      this.s = s == null ? "" : s;
    }

    void expectEnd() {
      skipWs();
      if (pos < s.length()) {
        throw error("unexpected '" + s.charAt(pos) + "'");
      }
    }

    Node parseTernary() {
      Node cond = parseOr();
      if (match("?")) {
        Node yes = parseTernary();
        expect(":");
        Node no = parseTernary();
        return new Ternary(cond, yes, no);
      }
      return cond;
    }

    Node parseOr() {
      Node n = parseAnd();
      while (match("||")) {
        n = new Binary("||", n, parseAnd());
      }
      return n;
    }

    Node parseAnd() {
      Node n = parseEquality();
      while (match("&&")) {
        n = new Binary("&&", n, parseEquality());
      }
      return n;
    }

    Node parseEquality() {
      Node n = parseComparison();
      while (true) {
        if (match("==")) {
          n = new Binary("==", n, parseComparison());
        } else if (match("!=")) {
          n = new Binary("!=", n, parseComparison());
        } else {
          return n;
        }
      }
    }

    Node parseComparison() {
      Node n = parseAdditive();
      while (true) {
        if (match("<=")) {
          n = new Binary("<=", n, parseAdditive());
        } else if (match(">=")) {
          n = new Binary(">=", n, parseAdditive());
        } else if (match("<")) {
          n = new Binary("<", n, parseAdditive());
        } else if (match(">")) {
          n = new Binary(">", n, parseAdditive());
        } else {
          return n;
        }
      }
    }

    Node parseAdditive() {
      Node n = parseMultiplicative();
      while (true) {
        if (match("+")) {
          n = new Binary("+", n, parseMultiplicative());
        } else if (peekMinus()) {
          pos++;
          n = new Binary("-", n, parseMultiplicative());
        } else {
          return n;
        }
      }
    }

    Node parseMultiplicative() {
      Node n = parseUnary();
      while (true) {
        if (match("*")) {
          n = new Binary("*", n, parseUnary());
        } else if (match("/")) {
          n = new Binary("/", n, parseUnary());
        } else if (match("%")) {
          n = new Binary("%", n, parseUnary());
        } else {
          return n;
        }
      }
    }

    Node parseUnary() {
      skipWs();
      if (pos < s.length() && s.charAt(pos) == '!' && !s.startsWith("!=", pos)) {
        pos++;
        return new Unary("!", parseUnary());
      }
      if (peekMinus()) {
        pos++;
        return new Unary("-", parseUnary());
      }
      return parsePostfix();
    }

    Node parsePostfix() {
      Node n = parsePrimary();
      while (true) {
        skipWs();
        if (match(".")) {
          String name = ident();
          if (name == null) {
            throw error("expected a name after '.'");
          }
          n = new Member(n, new Literal(name));
        } else if (match("[")) {
          Node key = parseTernary();
          expect("]");
          n = new Member(n, key);
        } else {
          return n;
        }
      }
    }

    Node parsePrimary() {
      skipWs();
      if (pos >= s.length()) {
        throw error("unexpected end of expression");
      }
      char c = s.charAt(pos);
      if (c == '(') {
        pos++;
        Node n = parseTernary();
        expect(")");
        return n;
      }
      if (c == '[') {
        pos++;
        List<Node> items = new ArrayList<>();
        skipWs();
        if (!match("]")) {
          do {
            items.add(parseTernary());
          } while (match(","));
          expect("]");
        }
        return new ListLit(items);
      }
      if (c == '\'' || c == '"') {
        return new Literal(string(c));
      }
      if (Character.isDigit(c)) {
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
          pos++;
        }
        try {
          return new Literal(Double.parseDouble(s.substring(start, pos)));
        } catch (NumberFormatException e) {
          throw error("bad number");
        }
      }
      String name = ident();
      if (name == null) {
        throw error("unexpected '" + c + "'");
      }
      switch (name) {
        case "true":
          return new Literal(Boolean.TRUE);
        case "false":
          return new Literal(Boolean.FALSE);
        case "null":
          return new Literal(null);
        default:
          break;
      }
      skipWs();
      if (pos < s.length() && s.charAt(pos) == '(') {
        if (!Functions.NAMES.contains(name)) {
          throw error("unknown function: " + name);
        }
        pos++;
        List<Node> args = new ArrayList<>();
        skipWs();
        if (!match(")")) {
          do {
            args.add(parseTernary());
          } while (match(","));
          expect(")");
        }
        return new Call(name, args);
      }
      return new Var(name);
    }

    private String string(char quote) {
      pos++;
      StringBuilder sb = new StringBuilder();
      while (pos < s.length()) {
        char c = s.charAt(pos++);
        if (c == quote) {
          return sb.toString();
        }
        if (c == '\\' && pos < s.length()) {
          char e = s.charAt(pos++);
          sb.append(switch (e) {
            case 'n' -> '\n';
            case 't' -> '\t';
            default -> e;
          });
        } else {
          sb.append(c);
        }
      }
      throw error("unterminated string");
    }

    private String ident() {
      skipWs();
      int start = pos;
      if (pos < s.length() && (Character.isLetter(s.charAt(pos)) || s.charAt(pos) == '_' || s.charAt(pos) == '$')) {
        pos++;
        while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
          pos++;
        }
      }
      return pos == start ? null : s.substring(start, pos);
    }

    private boolean peekMinus() {
      skipWs();
      return pos < s.length() && s.charAt(pos) == '-';
    }

    private boolean match(String token) {
      skipWs();
      if (s.startsWith(token, pos)) {
        // do not let '<' swallow '<=' etc.; callers try longer tokens first
        pos += token.length();
        return true;
      }
      return false;
    }

    private void expect(String token) {
      if (!match(token)) {
        throw error("expected '" + token + "'");
      }
    }

    private void skipWs() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
        pos++;
      }
    }

    private ExpressionException error(String msg) {
      return new ExpressionException(msg + " at position " + pos + " in: " + s);
    }
  }
}
