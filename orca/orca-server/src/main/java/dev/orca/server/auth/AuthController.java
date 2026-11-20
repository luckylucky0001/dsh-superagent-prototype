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

  public AuthController(AuthService auth) {
    this.auth = auth;
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
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too many failed attempts; try again in a few minutes");
    }
    String token = auth.login(String.valueOf(req.email()), String.valueOf(req.password())).orElse(null);
    if (token == null) {
      recent.add(now);
      throw new ApiException(HttpStatus.UNAUTHORIZED, "wrong e-mail or password");
    }
    failures.remove(key);
    ResponseCookie cookie = ResponseCookie.from(AuthFilter.COOKIE, token).httpOnly(true).path("/")
        .sameSite("Lax").secure(http.isSecure()).maxAge(Duration.ofDays(14)).build();
    res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    Member m = auth.verify(token, AuthService.SESSION).flatMap(t -> auth.member(t.memberId)).orElseThrow();
    return Map.of("member", MemberView.of(m), "token", token);
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
    String t = AuthFilter.token(req);
    if (t != null) {
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
