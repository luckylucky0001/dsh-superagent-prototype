package dev.fellowloop.agent.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fellowloop.protocol.Json;
import dev.fellowloop.protocol.ProviderStatus.UsageWindow;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodexUsageTest {

  @Test
  void readsTheUsageWindowsAndNothingElse() {
    // shape of a real account/rateLimits/read answer (codex-cli 0.171.0, ChatGPT pro plan)
    Object answer = Json.readMap("""
        {"ordinaryUsageAllowed":true,"accountId":"acct-should-not-leak",
         "rateLimits":{"limitName":null,"planType":"pro","rateLimitReachedType":null,
           "primary":{"usedPercent":46,"windowDurationMins":10080,"resetsAt":1795549362},
           "secondary":{"usedPercent":12,"windowDurationMins":300,"resetsAt":1795400000},
           "credits":{"hasCredits":true,"unlimited":false,"balance":"52900.99"}},
         "rateLimitsByLimitId":{}}""");
    List<UsageWindow> w = CodexProvider.usageWindows(answer);
    assertEquals(2, w.size());
    assertEquals(new UsageWindow(46, 10080L, 1795549362000L, false), w.get(0));
    assertEquals(12, w.get(1).usedPercent());
    assertFalse(w.toString().contains("acct") || w.toString().contains("52900"), "no account id or balance");
  }

  @Test
  void marksAReachedLimitAndToleratesMissingFields() {
    Object answer = Json.readMap("""
        {"rateLimits":{"rateLimitReachedType":"rate_limit_reached",
           "primary":{"usedPercent":100},"secondary":null}}""");
    List<UsageWindow> w = CodexProvider.usageWindows(answer);
    assertEquals(1, w.size());
    assertTrue(w.get(0).reached());
    assertEquals(null, w.get(0).resetsAt());
    assertTrue(CodexProvider.usageWindows(Json.readMap("{}")).isEmpty());
  }
}
