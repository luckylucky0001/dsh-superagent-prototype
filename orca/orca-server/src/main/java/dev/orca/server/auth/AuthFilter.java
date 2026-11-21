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
  /** Paths whose changes need a web session: personal access tokens may only read them. */
  static final List<String> ACCOUNT_ADMIN = List.of("/api/members", "/api/auth/tokens", "/api/runtimes", "/api/projects");

  private static final List<String> OPEN = List.of("/api/auth/login", "/api/daemon/register", "/api/daemon/ws", "/api/daemon/whoami",
      "/api/hooks/", "/api/health", "/api/mcp");

  /**
   * Changing what agents may do (their configuration, the shared skills, workflow definitions) needs a
   * signed-in person; a token can still ask agents, run workflows and answer the inbox.
   */
  static boolean configuresAgents(String path) {
    if (path.startsWith("/api/skills")) {
      return true;
    }
    if (path.startsWith("/api/agents")) {
      return !path.matches("/api/agents/[^/]+/ask");
    }
    if (path.startsWith("/api/workflows")) {
      return !path.matches("/api/workflows/[^/]+/runs") && !path.equals("/api/workflows/validate");
    }
    return false;
  }

  /** Authenticated inside the controller (session or task token). */
  private static boolean selfAuthenticated(String path) {
    return path.startsWith("/api/attachments/") && path.endsWith("/file");
  }

  private static final org.springframework.web.util.UrlPathHelper PATHS = new org.springframework.web.util.UrlPathHelper();

  private final AuthService auth;
  private final String publicOrigin;

  public AuthFilter(AuthService auth, dev.orca.server.support.OrcaProperties props) {
    this.auth = auth;
    this.publicOrigin = originOf(props.publicUrl());
  }

  /** {@code scheme://host[:port]} of a URL, lower case, or {@code null}. */
  static String originOf(String url) {
    try {
      java.net.URI u = java.net.URI.create(url.trim());
      if (u.getScheme() == null || u.getHost() == null) {
        return null;
      }
      return (u.getScheme() + "://" + u.getHost() + (u.getPort() < 0 ? "" : ":" + u.getPort())).toLowerCase(java.util.Locale.ROOT);
    } catch (RuntimeException e) {
      return null;
    }
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
    Optional<dev.orca.server.model.ApiToken> tok = auth.verify(presented, AuthService.SESSION)
        .or(() -> auth.verify(presented, AuthService.PERSONAL));
    Optional<Member> m = tok.flatMap(t -> auth.member(t.memberId));
    if (m.isEmpty()) {
      res.setStatus(401);
      res.setContentType("application/json");
      res.setCharacterEncoding("UTF-8");
      res.getWriter().write("{\"error\":\"not signed in\"}");
      return;
    }
    if (AuthService.PERSONAL.equals(tok.get().kind) && !"GET".equals(req.getMethod())
        && (ACCOUNT_ADMIN.stream().anyMatch(path::startsWith) || configuresAgents(path))) {
      // a leaked token must not be able to change passwords, mint more tokens, manage members or add machines
      res.setStatus(403);
      res.setContentType("application/json");
      res.setCharacterEncoding("UTF-8");
      res.getWriter().write("{\"error\":\"personal access tokens cannot manage members, tokens, runtimes, projects, agents, "
          + "skills or workflow definitions; sign in to the web app for that\"}");
      return;
    }
    if (!sameOrigin(req, publicOrigin)) {
      res.setStatus(403);
      res.setContentType("application/json");
      res.setCharacterEncoding("UTF-8");
      res.getWriter().write("{\"error\":\"cross-origin request refused\"}");
      return;
    }
    req.setAttribute(CurrentUser.ATTR, m.get());
    req.setAttribute(CurrentUser.KIND, tok.get().kind);
    chain.doFilter(req, res);
  }

  /**
   * The session cookie is {@code SameSite=Lax}, which still sends it from sibling subdomains (same site). A
   * browser marks every cross-origin write with {@code Origin}; cookie-authenticated writes must come from the
   * app's own origin. Requests without {@code Origin} (the CLI, scripts, older browsers on same-origin forms)
   * and bearer-token requests (no ambient credential) are not affected.
   */
  static boolean sameOrigin(HttpServletRequest req, String publicOrigin) {
    String method = req.getMethod();
    if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
      return true;
    }
    String h = req.getHeader("Authorization");
    if (h != null && h.startsWith("Bearer ")) {
      return true;
    }
    String origin = req.getHeader("Origin");
    if (origin == null) {
      return true;
    }
    if (publicOrigin != null && publicOrigin.equals(originOf(origin))) {
      return true; // behind a proxy that rewrites Host
    }
    String host = req.getHeader("Host");
    try {
      java.net.URI o = java.net.URI.create(origin.trim());
      if (o.getHost() == null || host == null) {
        return false;
      }
      String authority = o.getPort() < 0 ? o.getHost() : o.getHost() + ":" + o.getPort();
      String expected = host.trim().toLowerCase(java.util.Locale.ROOT);
      String got = authority.toLowerCase(java.util.Locale.ROOT);
      // a default port may be spelt out in one header and left out in the other
      return got.equals(expected) || got.equals(expected.replaceFirst(":(80|443)$", ""))
          || expected.equals(got.replaceFirst(":(80|443)$", ""));
    } catch (IllegalArgumentException e) {
      return false;
    }
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
