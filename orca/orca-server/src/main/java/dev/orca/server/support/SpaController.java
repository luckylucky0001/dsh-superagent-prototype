package dev.orca.server.support;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the single-page app for client-side routes. */
@Controller
public class SpaController {
  @GetMapping({"/issues/**", "/inbox/**", "/agents/**", "/runtimes/**", "/workflows/**", "/runs/**", "/settings/**",
      "/login"})
  public String index() {
    return "forward:/index.html";
  }
}
