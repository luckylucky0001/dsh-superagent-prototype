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

  @Transactional
  public Optional<String> login(String email, String password) {
    return members.findByEmailIgnoreCase(email)
        .filter(m -> m.passwordHash != null && encoder.matches(password, m.passwordHash))
        .map(m -> issue(SESSION, m.id, null, "web session", SESSION_TTL));
  }

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
    Optional<ApiToken> t = verify(code, REGISTRATION).filter(x -> x.usedAt == null);
    t.ifPresent(x -> {
      x.usedAt = Ids.now();
      tokens.save(x);
    });
    return t.isPresent();
  }

  @Transactional
  public void revoke(String plain) {
    tokens.findByTokenHash(hash(plain)).ifPresent(tokens::delete);
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
      default -> "orca_t_";
    };
  }
}
