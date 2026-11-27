package com.taskora.board.domain.model;

import java.util.Locale;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Fond de tableau : une des dix valeurs historiques ; toute autre valeur est ignorée. */
public enum Background {
  /** Valeur par défaut. */
  AURORA,
  /** Coucher de soleil. */
  SUNSET,
  /** Océan. */
  OCEAN,
  /** Forêt. */
  FOREST,
  /** Minuit. */
  MIDNIGHT,
  /** Pêche. */
  PEACH,
  /** Lagon. */
  LAGOON,
  /** Baie. */
  BERRY,
  /** Graphite. */
  GRAPHITE,
  /** Sable. */
  SAND;

  /**
   * Valeur stockée et exposée ({@code aurora}, {@code ocean}…).
   *
   * @return valeur en minuscules
   */
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Valeur reçue dans un corps : exactement une des dix valeurs (sensible à la casse), sinon
   * absente.
   *
   * @param raw valeur reçue (non chaîne : {@code null})
   * @return le fond, ou vide si la valeur doit être ignorée
   */
  public static Optional<Background> parse(@Nullable String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    for (Background b : values()) {
      if (b.value().equals(raw)) {
        return Optional.of(b);
      }
    }
    return Optional.empty();
  }

  /**
   * Valeur lue en base.
   *
   * @param stored colonne {@code board.background}
   * @return le fond correspondant
   */
  public static Background fromStored(String stored) {
    return parse(stored).orElse(AURORA);
  }
}
