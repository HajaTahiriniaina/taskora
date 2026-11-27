package com.taskora.board.adapter.in.web;

import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Octets du corps, lus sans analyse par l'adapter web (B-ADR-3 point 3) ; l'analyse est faite par
 * le cas d'utilisation, après la politique d'accès.
 *
 * @param bytes corps brut (vide si absent)
 */
public record RawJsonBody(byte[] bytes) {

  /**
   * Corps reçu, vide s'il est absent.
   *
   * @param bytes octets, ou {@code null}
   * @return corps
   */
  public static RawJsonBody of(byte @Nullable [] bytes) {
    return new RawJsonBody(bytes == null ? new byte[0] : bytes);
  }

  /** Égalité sur le contenu des octets. */
  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof RawJsonBody body && Arrays.equals(bytes, body.bytes);
  }

  /** Empreinte sur le contenu des octets. */
  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  /** Longueur seulement : le corps peut contenir des données personnelles. */
  @Override
  public String toString() {
    return "RawJsonBody[length=" + bytes.length + "]";
  }
}
