package com.taskora.board.adapter.in.web;

/**
 * Identifiant de chemin (E12) : entier décimal strictement positif tenant dans un {@code int} ;
 * sinon 404 {@code Ressource introuvable.}, après l'authentification.
 */
public final class PathIdConverter {
  private PathIdConverter() {}

  /**
   * Convertit un segment de chemin.
   *
   * @param segment segment brut ({@code abc}, {@code 0}, {@code -1}, {@code 99999999999} refusés)
   * @return identifiant
   * @throws com.taskora.board.domain.exception.ResourceNotFoundException segment refusé
   */
  public static int parse(String segment) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
