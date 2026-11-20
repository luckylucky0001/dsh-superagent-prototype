package dev.orca.server.auth;

import dev.orca.server.model.Member;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a session (cookie {@code orca_session} or {@code Authorization: Bearer}) for {@code /api/**},
 * except login, daemon registration, the daemon socket (authenticated by its own handshake) and webhooks.
 */
@Component
@Order(1)
public class AuthFilter extends OncePerRequestFilter {

  public static final String COOKIE = "orca_session";
  private static final List<String> OPEN = List.of("/api/auth/login", "/api/daemon/register", "/api/daemon/ws",
      "/api/hooks/", "/api/health", "/api/mcp");

  /** Authenticated inside the controller (session or task token). */
  private static boolean selfAuthenticated(String path) {
    return path.startsWith("/api/attachments/") && path.endsWith("/file");
  }

  private static final org.springframework.web.util.UrlPathHelper PATHS = new org.springframework.web.util.UrlPathHelper();

  private final AuthService auth;

  public AuthFilter(AuthService auth) {
    this.auth = auth;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String raw = req.getRequestURI();
    // Decide on the same decoded, normalised path Spring routes on; refuse ambiguous API paths outright.
    String path = PATHS.getLookupPathForRequest(req);
    String lower = raw.toLowerCase(java.util.Locale.ROOT);
    if ((path.startsWith("/api") || lower.startsWith("/api") || lower.contains("%"))
        && (raw.contains(";") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e")
        || !raw.equals(req.getContextPath() + path) && path.startsWith("/api"))) {
      res.setStatus(400);
      res.setContentType("application/json");
      res.setCharacterEncoding("UTF-8");
      res.getWriter().write("{\"error\":\"malformed path\"}");
      return;
    }
    if (!path.startsWith("/api/") || OPEN.stream().anyMatch(path::startsWith) || selfAuthenticated(path)) {
      chain.doFilter(req, res);
      return;
    }
    String presented = token(req);
    Optional<Member> m = auth.verify(presented, AuthService.SESSION)
        .or(() -> auth.verify(presented, AuthService.PERSONAL))
        .flatMap(t -> auth.member(t.memberId));
    if (m.isEmpty()) {
      res.setStatus(401);
      res.setContentType("application/json");
      res.setCharacterEncoding("UTF-8");
      res.getWriter().write("{\"error\":\"not signed in\"}");
      return;
    }
    req.setAttribute(CurrentUser.ATTR, m.get());
    chain.doFilter(req, res);
  }

  public static String token(HttpServletRequest req) {
    String h = req.getHeader("Authorization");
    if (h != null && h.startsWith("Bearer ")) {
      return h.substring(7).trim();
    }
    if (req.getCookies() != null) {
      for (Cookie c : req.getCookies()) {
        if (COOKIE.equals(c.getName())) {
          return c.getValue();
        }
      }
    }
    return null;
  }
}
