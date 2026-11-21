package dev.fellowloop.server.support;

import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Runs a transactional action again when it lost an optimistic-lock race (e.g. a person cancels while the
 * runtime's events update the same task); the second attempt reads the current row.
 */
public final class Retry {
  private Retry() {}

  public static <T> T onConflict(Supplier<T> action) {
    for (int attempt = 1; ; attempt++) {
      try {
        return action.get();
      } catch (OptimisticLockingFailureException e) {
        if (attempt >= 3) {
          throw e;
        }
      }
    }
  }
}
