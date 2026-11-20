package dev.orca.server.task;

import java.util.List;
import java.util.Set;

public final class TaskStatus {
  public static final String QUEUED = "queued";
  public static final String DISPATCHED = "dispatched";
  public static final String RUNNING = "running";
  public static final String WAITING_INPUT = "waiting_input";
  public static final String SUCCEEDED = "succeeded";
  public static final String FAILED = "failed";
  public static final String CANCELLED = "cancelled";
  public static final String TIMED_OUT = "timed_out";

  public static final List<String> ACTIVE = List.of(DISPATCHED, RUNNING, WAITING_INPUT);
  public static final Set<String> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELLED, TIMED_OUT);

  private TaskStatus() {}
}
