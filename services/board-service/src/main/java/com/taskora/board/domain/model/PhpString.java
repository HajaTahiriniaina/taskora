package com.taskora.board.domain.model;

import java.util.Locale;

/** Fonctions de chaîne de PHP reproduites (copie d'identity, pas de dépendance entre modules). */
public final class PhpString {
  private static final String TRIM_CHARS = " \t\n\r\0\u000B";

  private PhpString() {}

  /**
   * {@code trim} de PHP : espace, \t, \n, \r, \0, \x0B aux deux extrémités.
   *
   * @param s chaîne
   * @return chaîne trimée
   */
  public static String trim(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && TRIM_CHARS.indexOf(s.charAt(start)) >= 0) {
      start++;
    }
    while (end > start && TRIM_CHARS.indexOf(s.charAt(end - 1)) >= 0) {
      end--;
    }
    return s.substring(start, end);
  }

  /**
   * {@code mb_strlen} : longueur en points de code.
   *
   * @param s chaîne
   * @return nombre de points de code
   */
  public static int length(String s) {
    return s.codePointCount(0, s.length());
  }

  /**
   * {@code mb_substr($s, 0, $max)} : troncature en points de code.
   *
   * @param s chaîne
   * @param max longueur maximale
   * @return chaîne tronquée
   */
  public static String truncate(String s, int max) {
    if (length(s) <= max) {
      return s;
    }
    return s.substring(0, s.offsetByCodePoints(0, max));
  }

  /**
   * {@code mb_strtolower}.
   *
   * @param s chaîne
   * @return chaîne en minuscules
   */
  public static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }
}
