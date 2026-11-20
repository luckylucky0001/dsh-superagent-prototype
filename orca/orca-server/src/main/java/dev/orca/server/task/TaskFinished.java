package dev.orca.server.task;

import dev.orca.server.model.Task;

/** Published inside the transaction that ends a task. */
public record TaskFinished(Task task) {}
