package dev.fellowloop.server.task;

import dev.fellowloop.server.model.Task;

/** Published inside the transaction that ends a task. */
public record TaskFinished(Task task) {}
