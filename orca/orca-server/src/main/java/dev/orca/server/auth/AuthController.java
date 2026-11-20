package dev.orca.server.auth;

import dev.orca.server.model.Member;
import dev.orca.server.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService auth;
  private final dev.orca.server.audit.AuditService audit;

  public AuthController(AuthService auth, dev.orca.server.audit.AuditService audit) {
    this.auth = auth;
    this.audit = audit;
  }

  public record LoginRequest(String email, String password) {}

  public record MemberView(String id, String email, String name, String role) {
    public static MemberView of(Member m) {
      return new MemberView(m.id, m.email, m.name, m.role);
    }
  }

  private static final int MAX_FAILURES = 10;
  private static final long WINDOW_MS = 15 * 60_000L;
  private final java.util.concurrent.ConcurrentHashMap<String, java.util.Deque<Long>> failures =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final java.util.concurrent.atomic.AtomicLong failureWindow = new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicInteger failuresInWindow = new java.util.concurrent.atomic.AtomicInteger();

  /**
   * Failed sign-ins are audited without trusting the typed name as an actor (it is shown as "login") and
   * at most 60 a minute, so a password-guessing flood cannot flood the log; a name that is not an e-mail
   * address (often a password typed into the wrong box) is not stored.
   */
  private void auditFailure(String action, String login) {
    long minute = System.currentTimeMillis() / 60_000;
    if (failureWindow.getAndSet(minute) != minute) {
      failuresInWindow.set(0);
    }
    int n = failuresInWindow.incrementAndGet();
    if (n > 60) {
      if (n == 61) {
        audit.record(null, null, "auth.audit_throttled", "member", null, Map.of("reason", "more than 60 failed sign-ins this minute"));
      }
      return;
    }
    String l = String.valueOf(login).trim();
    String shown = l.matches("[^\\s@]{1,64}@[^\\s@]{1,190}") ? l : "(not an e-mail address)";
    audit.record(null, null, action, "member", null, Map.of("login", shown));
  }

  @PostMapping("/login")
  public Map<String, Object> login(@RequestBody LoginRequest req, HttpServletRequest http, HttpServletResponse res) {
    String key = String.valueOf(req.email()).toLowerCase() + "|" + http.getRemoteAddr();
    long now = System.currentTimeMillis();
    if (failures.size() > 10_000) {
      failures.entrySet().removeIf(en -> en.getValue().stream().allMatch(t -> t < now - WINDOW_MS));
    }
    java.util.Deque<Long> recent = failures.computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
    recent.removeIf(t -> t < now - WINDOW_MS);
    if (recent.size() >= MAX_FAILURES) {
      if (recent.size() == MAX_FAILURES) {
        recent.add(now); // one audit row per lockout, not one per blocked request
        auditFailure("auth.login_blocked", req.email());
      }
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too many failed attempts; try again in a few minutes");
    }
    String token = auth.login(String.valueOf(req.email()), String.valueOf(req.password())).orElse(null);
    if (token == null) {
      recent.add(now);
      auditFailure("auth.login_failed", req.email());
      throw new ApiException(HttpStatus.UNAUTHORIZED, "wrong e-mail or password");
    }
    failures.remove(key);
    ResponseCookie cookie = ResponseCookie.from(AuthFilter.COOKIE, token).httpOnly(true).path("/")
        .sameSite("Lax").secure(http.isSecure()).maxAge(Duration.ofDays(14)).build();
    res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    Member m = auth.verify(token, AuthService.SESSION).flatMap(t -> auth.member(t.memberId)).orElseThrow();
    audit.record(m.id, m.name, "auth.login", "member", m.id, Map.of());
    return Map.of("member", MemberView.of(m), "token", token);
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
    String t = AuthFilter.token(req);
    if (t != null) {
      auth.verify(t, AuthService.SESSION).or(() -> auth.verify(t, AuthService.PERSONAL))
          .ifPresent(tok -> auth.member(tok.memberId).ifPresent(m -> audit.record(m.id, m.name, "auth.logout", "member",
              m.id, Map.of("via", tok.kind))));
      auth.revoke(t);
    }
    res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(AuthFilter.COOKIE, "").path("/").maxAge(0).build().toString());
    return Map.of("ok", true);
  }

  @GetMapping("/me")
  public MemberView me() {
    return MemberView.of(CurrentUser.get());
  }
}
