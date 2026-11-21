package dev.orca.server.auth;

import dev.orca.server.model.ApiToken;
import dev.orca.server.model.Member;
import dev.orca.server.repo.ApiTokenRepo;
import dev.orca.server.repo.MemberRepo;
import dev.orca.server.support.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Passwords, sessions, daemon tokens and one-time registration codes. Only token hashes are stored. */
@Service
public class AuthService {

  public static final String SESSION = "session";
  public static final String DAEMON = "daemon";
  public static final String REGISTRATION = "registration";
  public static final String TASK = "task";
  /** Personal access token for the CLI and scripts; acts as its member. */
  public static final String PERSONAL = "personal";

  private static final Duration SESSION_TTL = Duration.ofDays(14);
  private static final Duration REGISTRATION_TTL = Duration.ofHours(1);

  private final MemberRepo members;
  private final ApiTokenRepo tokens;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public AuthService(MemberRepo members, ApiTokenRepo tokens) {
    this.members = members;
    this.tokens = tokens;
  }

  public String hashPassword(String raw) {
    return encoder.encode(raw);
  }

  public boolean passwordMatches(Member m, String raw) {
    return raw != null && m.passwordHash != null && encoder.matches(raw, m.passwordHash);
  }

  /** Signs a member out everywhere (web sessions and personal access tokens), except the token {@code keep}. */
  @Transactional
  public void revokeMemberTokens(String memberId, String keep) {
    String keepHash = keep == null ? null : hash(keep);
    tokens.findByMemberId(memberId).stream()
        .filter(t -> SESSION.equals(t.kind) || PERSONAL.equals(t.kind))
        .filter(t -> !t.tokenHash.equals(keepHash))
        .forEach(tokens::delete);
  }

  @Transactional
  public Optional<String> login(String email, String password) {
    // only plain ASCII addresses: the database's case folding would otherwise match look-alikes such as a
    // long s, which also gave each variant its own failed-attempt counter
    Optional<dev.orca.server.model.Member> m = email == null || !email.matches("\\p{ASCII}+")
        ? Optional.empty() : members.findByEmailIgnoreCase(email);
    if (m.isEmpty() || m.get().passwordHash == null) {
      encoder.matches(String.valueOf(password), DUMMY_HASH); // same time as a wrong password: no address probing
      return Optional.empty();
    }
    return m.filter(x -> encoder.matches(password, x.passwordHash))
        .map(x -> issue(SESSION, x.id, null, "web session", SESSION_TTL));
  }

  /** A hash of a random string, checked against when there is no such member. */
  private final String DUMMY_HASH = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
      .encode(Ids.random(24));

  /** Creates a token and returns its plaintext; only the hash is persisted. */
  @Transactional
  public String issue(String kind, String memberId, String runtimeId, String label, Duration ttl) {
    String plain = prefix(kind) + Ids.random(40);
    ApiToken t = new ApiToken();
    t.id = Ids.next("tok");
    t.kind = kind;
    t.tokenHash = hash(plain);
    t.memberId = memberId;
    t.runtimeId = runtimeId;
    t.label = label;
    t.createdAt = Ids.now();
    t.expiresAt = ttl == null ? null : t.createdAt + ttl.toMillis();
    tokens.save(t);
    return plain;
  }

  public String issueRegistrationCode(String memberId, String label) {
    return issue(REGISTRATION, memberId, null, label, REGISTRATION_TTL);
  }

  @Transactional(readOnly = true)
  public Optional<ApiToken> verify(String plain, String kind) {
    if (plain == null || plain.isBlank()) {
      return Optional.empty();
    }
    return tokens.findByTokenHash(hash(plain))
        .filter(t -> t.kind.equals(kind))
        .filter(t -> t.expiresAt == null || t.expiresAt > Ids.now());
  }

  /** Consumes a registration code; returns false if it is unknown, used or expired. */
  @Transactional
  public boolean consumeRegistration(String code) {
    // one atomic update: concurrent registrations with the same code cannot both succeed
    return code != null && tokens.markUsed(hash(code), REGISTRATION, Ids.now()) == 1;
  }

  @Transactional
  public void revoke(String plain) {
    tokens.findByTokenHash(hash(plain)).ifPresent(tokens::delete);
  }

  /** A token for the MCP endpoint, bound to one task through its label. */
  @Transactional
  public String issueTaskToken(String taskId, Duration ttl) {
    return issue(TASK, null, null, taskId, ttl);
  }

  @Transactional
  public void revokeTaskTokens(String taskId) {
    tokens.deleteAll(tokens.findByKindAndLabel(TASK, taskId));
  }

  @Transactional
  public void revokeRuntime(String runtimeId) {
    tokens.deleteAll(tokens.findByRuntimeId(runtimeId));
  }

  public Optional<Member> member(String id) {
    return id == null ? Optional.empty() : members.findById(id);
  }

  public static String hash(String plain) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plain.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String prefix(String kind) {
    return switch (kind) {
      case SESSION -> "orca_s_";
      case DAEMON -> "orca_d_";
      case REGISTRATION -> "orca_r_";
      case PERSONAL -> "orca_p_";
      default -> "orca_t_";
    };
  }
}
