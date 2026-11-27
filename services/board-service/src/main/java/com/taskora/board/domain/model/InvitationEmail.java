package com.taskora.board.domain.model;

import com.taskora.board.domain.exception.DomainMessages;
import com.taskora.board.domain.exception.ValidationException;

/**
 * E-mail d'ajout ou d'invitation : trim PHP, obligatoire, tronqué à 180 points de code, mis en
 * minuscules ({@code mb_strtolower}), puis {@code FILTER_VALIDATE_EMAIL} de PHP (porté d'identity).
 *
 * @param value e-mail normalisé
 */
public record InvitationEmail(String value) {
  /** Longueur maximale en points de code (avant les minuscules). */
  public static final int MAX_LENGTH = 180;

  /**
   * Normalise et valide un e-mail reçu.
   *
   * @param raw valeur brute
   * @return e-mail normalisé
   * @throws com.taskora.board.domain.exception.ValidationException {@code Le champ "email" est
   *     obligatoire.} ou {@code Adresse e-mail invalide.}
   */
  public static InvitationEmail of(String raw) {
    String trimmed = PhpString.trim(raw);
    if (trimmed.isEmpty()) {
      throw new ValidationException(DomainMessages.required("email"));
    }
    String email = PhpString.lower(PhpString.truncate(trimmed, MAX_LENGTH));
    if (!PhpEmailValidator.isValid(email)) {
      throw new ValidationException(DomainMessages.INVALID_EMAIL);
    }
    return new InvitationEmail(email);
  }
}
