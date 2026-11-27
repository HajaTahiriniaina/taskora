package com.taskora.board.adapter.in.web;

import com.taskora.board.domain.exception.ResourceNotFoundException;
import java.util.regex.Pattern;

/**
 * Identifiant de chemin (E12) : entier décimal strictement positif tenant dans un {@code int} ;
 * sinon 404 {@code Ressource introuvable.}, après l'authentification.
 */
public final class PathIdConverter {
  /** Entier décimal sans signe ni zéro initial, au plus 10 chiffres. */
  private static final Pattern DIGITS = Pattern.compile("[1-9][0-9]{0,9}");

  private PathIdConverter() {}

  /**
   * Convertit un segment de chemin.
   *
   * @param segment segment brut ({@code abc}, {@code 0}, {@code -1}, {@code 99999999999} refusés)
   * @return identifiant
   * @throws com.taskora.board.domain.exception.ResourceNotFoundException segment refusé
   */
  public static int parse(String segment) {
    if (!DIGITS.matcher(segment).matches() || Long.parseLong(segment) > Integer.MAX_VALUE) {
      throw new ResourceNotFoundException();
    }
    return Integer.parseInt(segment);
  }
}
