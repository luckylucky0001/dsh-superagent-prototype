package dev.orca.daemon;

import dev.orca.agent.core.AgentProvider;
import dev.orca.agent.trae.TraeConfig;
import dev.orca.agent.trae.TraeProvider;
import java.time.Duration;
import java.nio.file.Path;

final class Providers {
  private Providers() {}

  static AgentProvider trae(DaemonConfig c) {
    return new TraeProvider(new TraeConfig(c.traeCommand(), c.env(), "orca", Daemon.VERSION, Duration.ofSeconds(90),
        c.recordDir() == null ? null : Path.of(c.recordDir()), c.workRootPath()));
  }
}
