package dev.orca.server.auth;

import dev.orca.server.model.ApiToken;
import dev.orca.server.model.Member;
import dev.orca.server.repo.ApiTokenRepo;
import dev.orca.server.support.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Personal access tokens for the {@code orca} CLI and scripts. Each member manages their own. */
@RestController
@RequestMapping("/api/auth/tokens")
public class TokenController {

  private final AuthService auth;
  private final ApiTokenRepo tokens;

  private final dev.orca.server.audit.AuditService audit;

  public TokenController(AuthService auth, ApiTokenRepo tokens, dev.orca.server.audit.AuditService audit) {
    this.auth = auth;
    this.tokens = tokens;
    this.audit = audit;
  }

  public record TokenInput(String label, Integer days) {}

  public record TokenView(String id, String label, long createdAt, Long expiresAt) {
    static TokenView of(ApiToken t) {
      return new TokenView(t.id, t.label, t.createdAt, t.expiresAt);
    }
  }

  @GetMapping
  public List<TokenView> list() {
    return tokens.findByKindAndMemberIdOrderByCreatedAtDesc(AuthService.PERSONAL, CurrentUser.id()).stream()
        .map(TokenView::of).toList();
  }

  /** Returns the token once; only its hash is stored. {@code days}: 1–365, default 90. */
  @PostMapping
  @Transactional
  public Map<String, Object> create(@RequestBody TokenInput in) {
    Member me = CurrentUser.get();
    String label = in == null || in.label() == null || in.label().isBlank() ? "CLI" : in.label().trim();
    if (label.length() > 80) {
      throw ApiException.bad("keep the label under 80 characters");
    }
    int days = in == null || in.days() == null ? 90 : in.days();
    if (days < 1 || days > 365) {
      throw ApiException.bad("a token lives 1 to 365 days");
    }
    String plain = auth.issue(AuthService.PERSONAL, me.id, null, label, Duration.ofDays(days));
    ApiToken t = auth.verify(plain, AuthService.PERSONAL).orElseThrow();
    audit.record("token.create", "token", t.id, Map.of("label", label, "days", days));
    return Map.of("token", plain, "info", TokenView.of(t));
  }

  @DeleteMapping("/{id}")
  @Transactional
  public Map<String, Object> revoke(@PathVariable String id) {
    ApiToken t = tokens.findById(id).filter(x -> AuthService.PERSONAL.equals(x.kind))
        .filter(x -> CurrentUser.id().equals(x.memberId)).orElseThrow(() -> ApiException.notFound("token"));
    tokens.delete(t);
    audit.record("token.revoke", "token", t.id, Map.of("label", t.label));
    return Map.of("ok", true);
  }
}
