package dev.fellowloop.daemon;

import dev.fellowloop.agent.core.AgentProvider;
import dev.fellowloop.agent.trae.TraeConfig;
import dev.fellowloop.agent.trae.TraeProvider;
import java.time.Duration;
import java.nio.file.Path;

final class Providers {
  private Providers() {}

  static AgentProvider trae(DaemonConfig c) {
    return new TraeProvider(new TraeConfig(c.traeCommand(), c.env(), "fellowloop", Daemon.VERSION, Duration.ofSeconds(90),
        c.recordDir() == null ? null : Path.of(c.recordDir()), c.workRootPath()));
  }
}
