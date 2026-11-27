package com.taskora.board.domain.model;

import com.taskora.board.domain.exception.DomainMessages;
import com.taskora.board.domain.exception.ValidationException;

/**
 * Titre de tableau : trim PHP, obligatoire, tronqué à 160 points de code (contrat {@code title}).
 *
 * @param value titre normalisé
 */
public record BoardTitle(String value) {
  /** Longueur maximale en points de code. */
  public static final int MAX_LENGTH = 160;

  /**
   * Normalise un titre reçu.
   *
   * @param raw valeur brute (chaîne JSON)
   * @return titre trimé et tronqué
   * @throws com.taskora.board.domain.exception.ValidationException {@code Le champ "title" est
   *     obligatoire.} si vide après trim
   */
  public static BoardTitle of(String raw) {
    String trimmed = PhpString.trim(raw);
    if (trimmed.isEmpty()) {
      throw new ValidationException(DomainMessages.required("title"));
    }
    return new BoardTitle(PhpString.truncate(trimmed, MAX_LENGTH));
  }
}
