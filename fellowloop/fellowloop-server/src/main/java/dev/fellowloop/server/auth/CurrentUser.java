package dev.fellowloop.server.auth;

import dev.fellowloop.server.model.Member;
import dev.fellowloop.server.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The signed-in member of the current request, set by {@link AuthFilter}. */
public final class CurrentUser {
  static final String ATTR = "fellowloop.member";
  static final String KIND = "fellowloop.tokenKind";
  /** Request attribute: how the request authenticated ({@code session} or {@code personal}). */
  public static final String KIND_ATTRIBUTE = KIND;

  private CurrentUser() {}

  public static Member get() {
    ServletRequestAttributes a = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    HttpServletRequest req = a == null ? null : a.getRequest();
    Object m = req == null ? null : req.getAttribute(ATTR);
    if (!(m instanceof Member member)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "not signed in");
    }
    return member;
  }

  public static String id() {
    return get().id;
  }
}
