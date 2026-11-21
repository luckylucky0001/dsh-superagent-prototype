package dev.fellowloop.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RedactTest {

  @Test
  void secretsAreMaskedButOrdinaryFieldsAreNot() {
    assertEquals("git clone https://***@git.example.com/wms/core.git",
        Redact.secrets("git clone https://ci:glpat-abcdefghijklmnop@git.example.com/wms/core.git"));
    assertEquals("curl -H 'Authorization: *** ***'", Redact.secrets("curl -H 'Authorization: Bearer abcdefgh12345678'"));
    assertEquals("deploy --api-key=***", Redact.secrets("deploy --api-key=s3cr3tvalue"));
    assertEquals("authorization=***", Redact.secrets("authorization=xyz"));
    assertEquals("git -c user.name=Ann commit --author=Ann", Redact.secrets("git -c user.name=Ann commit --author=Ann"),
        "author is not a secret");
    assertEquals("export TOKEN=***", Redact.secrets("export TOKEN=abc"));
    assertEquals("push *** done", Redact.secrets("push ghp_abcdefghijklmnopqrstuvwxyz0123 done"));
  }
}
