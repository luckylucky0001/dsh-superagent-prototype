package dev.orca.agent.core;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * A turn's timeout that stops while the agent waits for a person (an approval or a question in the
 * inbox), so a slow answer does not use up the agent's working time.
 */
public final class TurnClock {

  private long pausedNanos;
  private int activePauses;
  private long pauseStartedAt;

  /** Starts a new turn. */
  public synchronized void reset() {
    pausedNanos = 0;
    activePauses = 0;
  }

  /** Runs {@code waitForPerson} with the clock stopped (pauses may overlap). */
  public <T> T paused(Supplier<T> waitForPerson) {
    synchronized (this) {
      if (activePauses++ == 0) {
        pauseStartedAt = System.nanoTime();
      }
    }
    try {
      return waitForPerson.get();
    } finally {
      synchronized (this) {
        if (--activePauses == 0) {
          pausedNanos += System.nanoTime() - pauseStartedAt;
        }
      }
    }
  }

  /** Time not counted so far, including a pause that is still going on. */
  synchronized long pausedSoFar() {
    return pausedNanos + (activePauses > 0 ? System.nanoTime() - pauseStartedAt : 0);
  }

  /** Waits for {@code f} for at most {@code timeout} of working time. */
  public <T> T await(CompletableFuture<T> f, Duration timeout)
      throws InterruptedException, ExecutionException, TimeoutException {
    long start = System.nanoTime();
    while (true) {
      long remaining = timeout.toNanos() + pausedSoFar() - (System.nanoTime() - start);
      if (remaining <= 0) {
        throw new TimeoutException();
      }
      try {
        return f.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS);
      } catch (TimeoutException e) {
        // re-check: the clock may have been stopped meanwhile
      }
    }
  }
}
