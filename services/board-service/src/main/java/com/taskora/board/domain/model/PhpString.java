package com.taskora.board.domain.model;

/** Fonctions de chaîne de PHP reproduites (copie d'identity, pas de dépendance entre modules). */
public final class PhpString {
  private PhpString() {}

  /**
   * {@code trim} de PHP : espace, \t, \n, \r, \0, \x0B aux deux extrémités.
   *
   * @param s chaîne
   * @return chaîne trimée
   */
  public static String trim(String s) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * {@code mb_strlen} : longueur en points de code.
   *
   * @param s chaîne
   * @return nombre de points de code
   */
  public static int length(String s) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * {@code mb_substr($s, 0, $max)} : troncature en points de code.
   *
   * @param s chaîne
   * @param max longueur maximale
   * @return chaîne tronquée
   */
  public static String truncate(String s, int max) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * {@code mb_strtolower}.
   *
   * @param s chaîne
   * @return chaîne en minuscules
   */
  public static String lower(String s) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
