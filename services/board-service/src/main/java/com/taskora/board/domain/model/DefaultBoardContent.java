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
    return List.of(
        new DefaultList("À faire", 0),
        new DefaultList("En cours", 1),
        new DefaultList("En revue", 2),
        new DefaultList("Terminé", 3));
  }

  /**
   * Les six étiquettes, dans l'ordre de création.
   *
   * @return étiquettes par défaut
   */
  public static List<DefaultLabel> labels() {
    return List.of(
        new DefaultLabel("Urgent", "#ef4444"),
        new DefaultLabel("Bug", "#f97316"),
        new DefaultLabel("Feature", "#6366f1"),
        new DefaultLabel("Design", "#ec4899"),
        new DefaultLabel("Backend", "#14b8a6"),
        new DefaultLabel("Idée", "#eab308"));
  }
}
