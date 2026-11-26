package com.taskora.identity.domain.model;

import java.util.Locale;

/** Fonctions de chaîne PHP reproduites (trim, mb_strlen, mb_substr, mb_strtolower). */
public final class PhpString {
  private static final String PHP_TRIM_CHARS = " \t\n\r\0\u000B";

  private PhpString() {}

  /**
   * Reproduit trim() de PHP : retire uniquement espace, \t, \n, \r, \0 et \x0B aux deux extrémités.
   */
  public static String trim(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && PHP_TRIM_CHARS.indexOf(s.charAt(start)) >= 0) {
      start++;
    }
    while (end > start && PHP_TRIM_CHARS.indexOf(s.charAt(end - 1)) >= 0) {
      end--;
    }
    return s.substring(start, end);
  }

  /** Longueur en points de code. */
  public static int length(String s) {
    return s.codePointCount(0, s.length());
  }

  /** Tronque comme mb_substr(s, 0, maxCodePoints), en points de code. */
  public static String truncate(String s, int maxCodePoints) {
    if (length(s) <= maxCodePoints) {
      return s;
    }
    return s.substring(0, s.offsetByCodePoints(0, maxCodePoints));
  }

  /** Met en minuscules comme mb_strtolower (règles Unicode, sans locale). */
  public static String toLower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }
}
