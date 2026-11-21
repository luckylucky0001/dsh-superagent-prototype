package dev.fellowloop.server.support;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the single-page app for client-side routes. */
@Controller
public class SpaController {
  @GetMapping({"/issues/**", "/inbox/**", "/agents/**", "/runtimes/**", "/workflows/**", "/runs/**", "/tasks/**", "/settings/**", "/usage/**",
      "/login"})
  public String index() {
    return "forward:/index.html";
  }

  /**
   * Any other single path segment without a dot (a mistyped link such as /isues): the app shows its own "not
   * found" page instead of the framework's error page. Files (with a dot), /api and deeper paths are not affected;
   * controllers with a fixed path always win over this pattern.
   */
  @GetMapping("/{segment:(?!api$)[A-Za-z0-9_-]+}")
  public String unknown() {
    return "forward:/index.html";
  }
}
