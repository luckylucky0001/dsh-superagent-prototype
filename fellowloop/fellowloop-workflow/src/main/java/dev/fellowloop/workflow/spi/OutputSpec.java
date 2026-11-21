package dev.fellowloop.workflow.spi;

/** A value a component publishes under {@code nodes.<id>.output.<name>}; used for variable hints. */
public record OutputSpec(String name, String type, String description) {}
