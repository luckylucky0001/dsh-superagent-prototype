package dev.orca.server.auth;

import dev.orca.server.model.Member;
import dev.orca.server.repo.MemberRepo;
import dev.orca.server.support.ApiException;
import dev.orca.server.support.Ids;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Team members. Owners and admins manage members; everyone can change their own name and password. */
@RestController
public class MemberController {
  static final Set<String> ROLES = Set.of("owner", "admin", "member");

  private final MemberRepo members;
  private final AuthService auth;
  private final dev.orca.server.realtime.Realtime realtime;

  public MemberController(MemberRepo members, AuthService auth, dev.orca.server.realtime.Realtime realtime) {
    this.members = members;
    this.auth = auth;
    this.realtime = realtime;
  }

  private AuthController.MemberView changed(Member m) {
    realtime.publish("member.updated", dev.orca.server.realtime.Realtime.ids("id", m.id));
    return AuthController.MemberView.of(m);
  }

  @GetMapping("/api/members")
  public List<AuthController.MemberView> list() {
    return members.findAll().stream().map(AuthController.MemberView::of).toList();
  }

  /** {@code currentPassword} is required when members change their own password. */
  public record MemberInput(String email, String name, String password, String role, String currentPassword) {}

  @PostMapping("/api/members")
  @Transactional
  public AuthController.MemberView create(@RequestBody MemberInput in) {
    requireAdmin();
    if (in.email() == null || !in.email().contains("@")) {
      throw ApiException.bad("a valid e-mail is required");
    }
    if (members.findByEmailIgnoreCase(in.email().trim()).isPresent()) {
      throw ApiException.bad("a member with this e-mail exists");
    }
    checkPassword(in.password());
    Member m = new Member();
    m.id = Ids.next("mem");
    m.email = in.email().trim();
    m.name = in.name() == null || in.name().isBlank() ? m.email.substring(0, m.email.indexOf('@')) : in.name().trim();
    m.role = in.role() != null && ROLES.contains(in.role()) && !"owner".equals(in.role()) ? in.role() : "member";
    m.passwordHash = auth.hashPassword(in.password());
    m.createdAt = Ids.now();
    return changed(members.save(m));
  }

  @PatchMapping("/api/members/{id}")
  @Transactional
  public AuthController.MemberView update(@PathVariable String id, @RequestBody MemberInput in,
      jakarta.servlet.http.HttpServletRequest req) {
    Member me = CurrentUser.get();
    boolean self = me.id.equals(id);
    if (!self) {
      requireAdmin();
    }
    Member m = members.findById(id).orElseThrow(() -> ApiException.notFound("member"));
    if (in.name() != null && !in.name().isBlank()) {
      m.name = in.name().trim();
    }
    if (in.role() != null && !in.role().equals(m.role)) {
      requireAdmin();
      if (!ROLES.contains(in.role()) || "owner".equals(m.role) || "owner".equals(in.role())) {
        throw ApiException.bad("the owner's role cannot be changed, and there is only one owner");
      }
      m.role = in.role();
    }
    if (in.password() != null) {
      checkPassword(in.password());
      if (self && !auth.passwordMatches(m, in.currentPassword())) {
        throw ApiException.bad("the current password is not correct");
      }
      m.passwordHash = auth.hashPassword(in.password());
      // every other session and token of this member stops working; you stay signed in here
      auth.revokeMemberTokens(m.id, self ? AuthFilter.token(req) : null);
    }
    return changed(members.save(m));
  }

  @DeleteMapping("/api/members/{id}")
  @Transactional
  public Map<String, Object> delete(@PathVariable String id) {
    requireAdmin();
    Member m = members.findById(id).orElseThrow(() -> ApiException.notFound("member"));
    if ("owner".equals(m.role) || m.id.equals(CurrentUser.id())) {
      throw ApiException.bad("the owner and yourself cannot be removed");
    }
    auth.revokeMemberTokens(m.id, null);
    members.delete(m);
    realtime.publish("member.updated", dev.orca.server.realtime.Realtime.ids("id", id, "deleted", true));
    return Map.of("ok", true);
  }

  private static void requireAdmin() {
    String role = CurrentUser.get().role;
    if (!"owner".equals(role) && !"admin".equals(role)) {
      throw new ApiException(HttpStatus.FORBIDDEN, "only owners and admins can manage members");
    }
  }

  private static void checkPassword(String p) {
    if (p == null || p.length() < 8) {
      throw ApiException.bad("passwords need at least 8 characters");
    }
  }
}
