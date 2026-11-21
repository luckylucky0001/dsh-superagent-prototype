package dev.fellowloop.daemon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agents' extra CLI arguments come from the server; the machine owner decides whether to honour them,
 * and arguments that change the sandbox or approvals are never honoured, so the permission tier holds.
 */
final class ExtraArgs {

  private static final List<String> FORBIDDEN_FLAGS = List.of("--yolo", "-y", "--dangerously-bypass-approvals-and-sandbox",
      "--dangerously-bypass-hook-trust", "--sandbox", "-s", "--ask-for-approval", "-a", "--permission-mode", "--add-dir",
      "--full-auto", "--cd", "-C", "--profile", "-p");
  /** Config keys an agent may set; everything else (sandbox, approvals, notify, providers, MCP, …) is dropped. */
  private static final List<String> ALLOWED_CONFIG = List.of("model_reasoning_summary", "model_verbosity",
      "hide_agent_reasoning", "show_raw_agent_reasoning", "model_supports_reasoning_summaries", "project_doc_max_bytes",
      "tools.web_search", "web_search", "file_opener");

  private ExtraArgs() {}

  static List<String> filter(List<String> args, boolean allowed) {
    if (!allowed || args == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (int i = 0; i < args.size(); i++) {
      String a = args.get(i).trim();
      String flag = a.contains("=") ? a.substring(0, a.indexOf('=')) : a;
      if (FORBIDDEN_FLAGS.contains(flag)) {
        if (!a.contains("=") && i + 1 < args.size() && !args.get(i + 1).startsWith("-") && takesValue(flag)) {
          i++;
        }
        continue;
      }
      if (flag.equals("-c") || flag.equals("--config")) {
        String kv = a.contains("=") && flag.equals("--config") ? a.substring(a.indexOf('=') + 1)
            : i + 1 < args.size() ? args.get(++i) : "";
        String key = kv.contains("=") ? kv.substring(0, kv.indexOf('=')).toLowerCase(Locale.ROOT) : kv;
        if (!ALLOWED_CONFIG.contains(key.trim())) {
          continue;
        }
        out.add("-c");
        out.add(kv);
        continue;
      }
      if (flag.equals("--enable") || flag.equals("--disable")) {
        out.add(a);
        if (!a.contains("=") && i + 1 < args.size()) {
          out.add(args.get(++i));
        }
        continue;
      }
      // anything else is dropped: only config overrides and feature flags are supported
    }
    return out;
  }

  private static boolean takesValue(String flag) {
    return !flag.equals("--yolo") && !flag.equals("-y") && !flag.startsWith("--dangerously") && !flag.equals("--full-auto");
  }
}
