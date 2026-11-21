package dev.fellowloop.server.support;

import java.security.SecureRandom;

/** Short, prefixed, URL-safe ids such as {@code tsk_8f3k2m9q1x}. */
public final class Ids {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();

  private Ids() {}

  public static String next(String prefix) {
    return prefix + "_" + random(12);
  }

  public static String random(int length) {
    char[] c = new char[length];
    for (int i = 0; i < length; i++) {
      c[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    }
    return new String(c);
  }

  public static long now() {
    return System.currentTimeMillis();
  }
}
