package com.taskora.board.domain.model;

/**
 * Encodage JSON identique à {@code json_encode} de PHP (R5) : {@code /} échappé, non-ASCII en
 * {@code \\uXXXX}, flottants entiers sans {@code .0}.
 */
public final class PhpJsonEncoder {
  private PhpJsonEncoder() {}

  /**
   * Encode une valeur (chaîne, nombre, booléen, {@code null}, liste, table ordonnée).
   *
   * @param value valeur
   * @return texte JSON
   */
  public static String encode(Object value) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
