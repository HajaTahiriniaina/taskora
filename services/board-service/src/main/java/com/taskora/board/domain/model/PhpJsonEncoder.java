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
    // Aucune colonne R5 (activity.data, custom_field.options, custom_field_value.value) n'est
    // écrite
    // en T1 : l'encodeur sera implémenté avec son golden PHP en T2/T3.
    throw new UnsupportedOperationException("Encodage PHP (R5) : T2/T3");
  }
}
