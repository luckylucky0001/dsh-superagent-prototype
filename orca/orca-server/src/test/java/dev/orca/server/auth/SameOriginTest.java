package dev.orca.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SameOriginTest {

  private static HttpServletRequest req(String method, Map<String, String> headers) {
    return (HttpServletRequest) Proxy.newProxyInstance(SameOriginTest.class.getClassLoader(),
        new Class<?>[] {HttpServletRequest.class}, (p, m, a) -> switch (m.getName()) {
          case "getMethod" -> method;
          case "getHeader" -> headers.get((String) a[0]);
          default -> null;
        });
  }

  @Test
  void cookieWritesMustComeFromTheAppsOwnOrigin() {
    String pub = AuthFilter.originOf("https://orca.example.sg/");
    assertTrue(AuthFilter.sameOrigin(req("POST", Map.of("Host", "orca.example.sg", "Origin", "https://orca.example.sg")), pub));
    assertTrue(AuthFilter.sameOrigin(req("POST", Map.of("Host", "localhost:5173", "Origin", "http://localhost:5173")), null));
    assertTrue(AuthFilter.sameOrigin(req("POST", Map.of("Host", "orca.example.sg:443", "Origin", "https://orca.example.sg")), null));
    assertTrue(AuthFilter.sameOrigin(req("POST", Map.of("Host", "127.0.0.1:8080", "Origin", "https://orca.example.sg")), pub),
        "a proxy that rewrites Host: the public URL is accepted");
    assertFalse(AuthFilter.sameOrigin(req("POST", Map.of("Host", "orca.example.sg", "Origin", "https://evil.example.sg")), pub),
        "a sibling subdomain is same-site, so the cookie is sent; the Origin check refuses it");
    assertFalse(AuthFilter.sameOrigin(req("DELETE", Map.of("Host", "orca.example.sg", "Origin", "null")), pub));
    assertTrue(AuthFilter.sameOrigin(req("GET", Map.of("Host", "orca.example.sg", "Origin", "https://evil.example.sg")), pub));
    assertTrue(AuthFilter.sameOrigin(req("POST", Map.of("Host", "orca.example.sg")), pub), "the CLI sends no Origin");
    assertTrue(AuthFilter.sameOrigin(req("POST", Map.of("Authorization", "Bearer x", "Origin", "https://evil.example.sg")), pub),
        "a bearer token is not an ambient credential");
  }
}
