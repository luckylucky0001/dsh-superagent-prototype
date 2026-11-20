package dev.orca.server.audit;

import dev.orca.server.auth.CurrentUser;
import dev.orca.server.model.AuditEvent;
import dev.orca.server.model.Member;
import dev.orca.server.repo.AuditRepo;
import dev.orca.server.support.Ids;
import dev.orca.server.support.JsonCols;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records who did what. Callers pass the action and its target; the actor and client address come from
 * the current request. Never record secrets: tokens, passwords and prompts stay out of {@code detail}.
 */
@Service
public class AuditService {
  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  private final AuditRepo events;

  public AuditService(AuditRepo events) {
    this.events = events;
  }

  /** Records an action by the signed-in member (or "system" outside a request). */
  public void record(String action, String targetType, String targetId, Map<String, Object> detail) {
    Member m = currentMember();
    record(m == null ? null : m.id, m == null ? "system" : m.name, action, targetType, targetId, detail);
  }

  /** Records an action by an explicit actor, e.g. a login before anyone is signed in. */
  public void record(String actorId, String actorName, String action, String targetType, String targetId,
      Map<String, Object> detail) {
    try {
      AuditEvent e = new AuditEvent();
      e.id = Ids.next("aud");
      e.at = Ids.now();
      e.actorId = actorId;
      e.actorName = actorName;
      e.action = action;
      e.targetType = targetType;
      e.targetId = targetId;
      e.detailJson = detail == null || detail.isEmpty() ? null : JsonCols.write(detail);
      e.ip = clientIp();
      events.save(e);
    } catch (RuntimeException ex) {
      // auditing must never break the action itself
      log.warn("could not record audit event {}: {}", action, ex.getMessage());
    }
  }

  private static Member currentMember() {
    try {
      return CurrentUser.get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String clientIp() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a)) {
      return null;
    }
    HttpServletRequest req = a.getRequest();
    String fwd = req.getHeader("X-Forwarded-For");
    String ip = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
    return ip != null && ip.length() > 64 ? ip.substring(0, 64) : ip;
  }
}
