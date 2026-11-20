package dev.orca.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.orca.agent.core.TurnClock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class TurnClockTest {

  @Test
  void timeWaitingForAPersonDoesNotCountAgainstTheTurn() throws Exception {
    TurnClock clock = new TurnClock();
    CompletableFuture<String> done = new CompletableFuture<>();
    // the "agent" waits 600 ms for a person, then finishes 100 ms later; the turn allows 400 ms of work
    Thread.ofVirtual().start(() -> {
      clock.paused(() -> {
        sleep(600);
        return null;
      });
      sleep(100);
      done.complete("ok");
    });
    assertEquals("ok", clock.await(done, Duration.ofMillis(400)));
  }

  @Test
  void workingTimeStillTimesOut() {
    TurnClock clock = new TurnClock();
    assertThrows(TimeoutException.class, () -> clock.await(new CompletableFuture<>(), Duration.ofMillis(200)));
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
