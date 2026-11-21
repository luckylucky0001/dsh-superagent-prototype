package dev.fellowloop.server.audit;

import java.util.regex.Pattern;

/** Removes credentials from text before it is written to the audit log. */
public final class Redact {
  private Redact() {}

  private static final Pattern URL_USERINFO = Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^/@\\s]+@");
  private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{8,}");
  private static final Pattern KEY_VALUE = Pattern.compile(
      "(?i)\\b([a-z0-9_.-]*(?:password|passwd|secret|token|api[_-]?key|access[_-]?key|auth(?!ors?\\b))[a-z0-9_.-]*)(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s&\"']+)");
  private static final Pattern KNOWN_TOKENS = Pattern.compile(
      "\\b(?:fellowloop_[a-z]_[A-Za-z0-9]{8,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{12,}"
          + "|sk-[A-Za-z0-9_-]{16,}|xox[abpr]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})");

  /** URL user info ({@code https://user:token@host}), bearer headers, {@code key=value} secrets and known token formats. */
  public static String secrets(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    String out = URL_USERINFO.matcher(s).replaceAll("$1***@");
    out = BEARER.matcher(out).replaceAll("$1 ***");
    out = KEY_VALUE.matcher(out).replaceAll("$1$2***");
    return KNOWN_TOKENS.matcher(out).replaceAll("***");
  }
}
