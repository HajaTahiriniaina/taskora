package com.taskora.board.domain.model;

import java.util.List;

/** Contenu par défaut d'un tableau créé avec {@code withDefaults} (SC-BOARD-001). */
public final class DefaultBoardContent {
  private DefaultBoardContent() {}

  /**
   * Liste par défaut.
   *
   * @param title titre
   * @param position position (0 à 3)
   */
  public record DefaultList(String title, int position) {}

  /**
   * Étiquette par défaut.
   *
   * @param name nom
   * @param color couleur {@code #rrggbb}
   */
  public record DefaultLabel(String name, String color) {}

  /**
   * Les quatre listes, dans l'ordre des positions.
   *
   * @return listes par défaut
   */
  public static List<DefaultList> lists() {
    throw new UnsupportedOperationException("BackCodeur");
  }

  /**
   * Les six étiquettes, dans l'ordre de création.
   *
   * @return étiquettes par défaut
   */
  public static List<DefaultLabel> labels() {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
