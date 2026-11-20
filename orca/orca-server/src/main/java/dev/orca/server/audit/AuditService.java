package dev.orca.server.audit;

import dev.orca.server.auth.CurrentUser;
import dev.orca.server.model.AuditEvent;
import dev.orca.server.model.Member;
import dev.orca.server.repo.AuditRepo;
import dev.orca.server.support.Ids;
import dev.orca.server.support.JsonCols;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records who did what. Callers pass the action and its target; the actor, the way they signed in and the
 * client address come from the current request. Inside a transaction the event is written only once that
 * transaction commits (an action that fails leaves no row), in a transaction of its own (a failing insert
 * never undoes the action). Text values are passed through {@link Redact}.
 */
@Service
public class AuditService {
  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  private final AuditRepo events;
  private final TransactionTemplate separate;
  private final long retentionDays;

  public AuditService(AuditRepo events, PlatformTransactionManager tx,
      @org.springframework.beans.factory.annotation.Value("${orca.audit.retention-days:365}") long retentionDays) {
    this.events = events;
    this.separate = new TransactionTemplate(tx);
    this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.retentionDays = retentionDays;
  }

  /** Records an action by the signed-in member (or "system" outside a request). */
  public void record(String action, String targetType, String targetId, Map<String, Object> detail) {
    Member m = currentMember();
    record(m == null ? null : m.id, m == null ? "system" : m.name, action, targetType, targetId, detail);
  }

  /** Records an action by an explicit actor, e.g. a login before anyone is signed in. */
  public void record(String actorId, String actorName, String action, String targetType, String targetId,
      Map<String, Object> detail) {
    AuditEvent e = new AuditEvent();
    e.id = Ids.next("aud");
    e.at = Ids.now();
    e.actorId = actorId;
    e.actorName = actorName == null ? null : clip(actorName, 200);
    e.action = action;
    e.targetType = targetType;
    e.targetId = targetId;
    Map<String, Object> d = new LinkedHashMap<>();
    if (detail != null) {
      detail.forEach((k, v) -> d.put(k, clean(v)));
    }
    String via = viaToken();
    if (via != null) {
      d.put("via", via);
    }
    e.detailJson = d.isEmpty() ? null : JsonCols.write(d);
    e.ip = clientIp();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          save(e);
        }
      });
    } else {
      save(e);
    }
  }

  private void save(AuditEvent e) {
    try {
      separate.executeWithoutResult(s -> events.save(e));
    } catch (RuntimeException ex) {
      // auditing must never break the action itself
      log.warn("could not record audit event {}: {}", e.action, ex.getMessage());
    }
  }

  /** Daily: drops events older than {@code orca.audit.retention-days} (default 365; 0 keeps everything). */
  @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT10M")
  public void retention() {
    if (retentionDays <= 0) {
      return;
    }
    long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
    separate.executeWithoutResult(s -> events.deleteByAtLessThan(cutoff));
  }

  @SuppressWarnings("unchecked")
  private static Object clean(Object v) {
    if (v instanceof String s) {
      return clip(Redact.secrets(s), 500);
    }
    if (v instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      ((Map<Object, Object>) m).forEach((k, x) -> out.put(String.valueOf(k), clean(x)));
      return out;
    }
    if (v instanceof List<?> l) {
      return l.stream().map(AuditService::clean).toList();
    }
    return v;
  }

  private static String clip(String s, int max) {
    return s.length() > max ? s.substring(0, max) + "..." : s;
  }

  private static Member currentMember() {
    try {
      return CurrentUser.get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** "token" when the request was made with a personal access token (the CLI or a script). */
  private static String viaToken() {
    HttpServletRequest req = request();
    Object kind = req == null ? null : req.getAttribute(CurrentUser.KIND_ATTRIBUTE);
    return "personal".equals(kind) ? "token" : null;
  }

  /**
   * The client address as the servlet container resolved it. Behind a reverse proxy, set
   * {@code server.forward-headers-strategy=native} (the default here): the container then takes the address
   * from {@code X-Forwarded-For} only when the request comes from a trusted internal proxy.
   */
  private static String clientIp() {
    HttpServletRequest req = request();
    String ip = req == null ? null : req.getRemoteAddr();
    return ip != null && ip.length() > 64 ? ip.substring(0, 64) : ip;
  }

  private static HttpServletRequest request() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
  }
}
