package dev.fellowloop.agent.core;

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
  private long turnStartedAt = System.nanoTime();

  /** Starts a new turn. */
  public synchronized void reset() {
    pausedNanos = 0;
    activePauses = 0;
    turnStartedAt = System.nanoTime();
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
    return await(f, timeout, System.nanoTime() - pausedSoFar());
  }

  /**
   * Waits for {@code f} until the turn has used {@code timeout} of working time since {@link #reset()}:
   * several requests that make up one turn share one deadline.
   */
  public <T> T awaitWithinTurn(CompletableFuture<T> f, Duration timeout)
      throws InterruptedException, ExecutionException, TimeoutException {
    long start;
    synchronized (this) {
      start = turnStartedAt;
    }
    return await(f, timeout, start);
  }

  /** {@code start}: the moment working time is counted from, minus the pauses counted so far. */
  private <T> T await(CompletableFuture<T> f, Duration timeout, long start)
      throws InterruptedException, ExecutionException, TimeoutException {
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
