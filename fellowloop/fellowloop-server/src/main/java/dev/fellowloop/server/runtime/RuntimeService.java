package dev.fellowloop.server.runtime;

import dev.fellowloop.protocol.ProviderStatus;
import dev.fellowloop.protocol.Wire;
import dev.fellowloop.server.auth.AuthService;
import dev.fellowloop.server.model.RuntimeHost;
import dev.fellowloop.server.realtime.Realtime;
import dev.fellowloop.server.repo.RuntimeRepo;
import dev.fellowloop.server.support.ApiException;
import dev.fellowloop.server.support.Ids;
import dev.fellowloop.server.support.JsonCols;
import dev.fellowloop.server.support.Views;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuntimeService {
  public static final String ONLINE = "online";
  public static final String OFFLINE = "offline";

  private final RuntimeRepo runtimes;
  private final AuthService auth;
  private final Realtime realtime;

  public RuntimeService(RuntimeRepo runtimes, AuthService auth, Realtime realtime) {
    this.runtimes = runtimes;
    this.auth = auth;
    this.realtime = realtime;
  }

  /** Exchanges a one-time code for a runtime and its long-lived token. */
  @Transactional
  public Wire.Welcome register(String code, String[] tokenOut) {
    return register(code, null, tokenOut);
  }

  /** As above, listing the runtime under {@code name} (trimmed, at most 80 characters) until its daemon says hello. */
  @Transactional
  public Wire.Welcome register(String code, String name, String[] tokenOut) {
    if (!auth.consumeRegistration(code)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "the registration code is invalid, used or expired");
    }
    RuntimeHost r = new RuntimeHost();
    r.id = Ids.next("rt");
    String n = name == null ? "" : name.strip();
    r.name = n.isEmpty() ? "new runtime" : n.length() > 80 ? n.substring(0, 80) : n;
    r.capacity = 2;
    r.status = OFFLINE;
    r.createdAt = Ids.now();
    runtimes.save(r);
    tokenOut[0] = auth.issue(AuthService.DAEMON, null, r.id, "daemon " + r.id, null);
    realtime.publish("runtime.updated", Realtime.ids("id", r.id));
    return new Wire.Welcome(r.id, 0, List.of());
  }

  @Transactional
  public RuntimeHost hello(String runtimeId, Wire.Hello h) {
    RuntimeHost r = runtimes.findById(runtimeId).orElseThrow(() -> ApiException.notFound("runtime"));
    r.name = h.name() == null || h.name().isBlank() ? r.name : h.name();
    r.hostname = h.hostname();
    r.os = h.os();
    r.daemonVersion = h.daemonVersion();
    r.capacity = Math.max(1, h.capacity());
    r.isolated = h.isolated();
    r.allowNetwork = h.allowNetwork();
    r.status = ONLINE;
    r.lastSeenAt = Ids.now();
    r.providersJson = JsonCols.write(h.providers());
    realtime.publish("runtime.updated", Realtime.ids("id", r.id));
    return runtimes.save(r);
  }

  @Transactional
  public void probed(String runtimeId, List<ProviderStatus> providers) {
    runtimes.findById(runtimeId).ifPresent(r -> {
      r.providersJson = JsonCols.write(providers);
      r.lastSeenAt = Ids.now();
      runtimes.save(r);
      realtime.publish("runtime.updated", Realtime.ids("id", r.id));
    });
  }

  @Transactional
  public void seen(String runtimeId) {
    runtimes.findById(runtimeId).ifPresent(r -> {
      r.lastSeenAt = Ids.now();
      if (!ONLINE.equals(r.status)) {
        r.status = ONLINE;
        realtime.publish("runtime.updated", Realtime.ids("id", r.id));
      }
      runtimes.save(r);
    });
  }

  @Transactional
  public void offline(String runtimeId) {
    runtimes.findById(runtimeId).ifPresent(r -> {
      r.status = OFFLINE;
      runtimes.save(r);
      realtime.publish("runtime.updated", Realtime.ids("id", r.id));
    });
  }

  @Transactional
  public void delete(String runtimeId) {
    auth.revokeRuntime(runtimeId);
    runtimes.deleteById(runtimeId);
    realtime.publish("runtime.updated", Realtime.ids("id", runtimeId, "deleted", true));
  }

  public Optional<RuntimeHost> find(String id) {
    return id == null ? Optional.empty() : runtimes.findById(id);
  }

  public List<RuntimeHost> all() {
    return runtimes.findAllByOrderByNameAsc();
  }

  /** The provider's status on this runtime, if the daemon reported it. */
  public Optional<ProviderStatus> provider(RuntimeHost r, String provider) {
    return Views.providers(r).stream().filter(p -> p.provider().equals(provider)).findFirst();
  }
}
