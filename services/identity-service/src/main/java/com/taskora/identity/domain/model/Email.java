package com.taskora.identity.domain.model;

import com.taskora.identity.domain.exception.ValidationException;

/** Adresse e-mail normalisée. */
public record Email(String value) {
  public static final int MAX_LENGTH = 180;
  static final String REQUIRED = "Le champ \"email\" est obligatoire.";
  static final String INVALID = "Adresse e-mail invalide.";

  /**
   * Normalise l'e-mail d'inscription : trim, obligatoire, 180 points de code, minuscules, format.
   */
  public static Email fromRegistration(String raw) {
    String trimmed = raw == null ? "" : PhpString.trim(raw);
    if (trimmed.isEmpty()) {
      throw new ValidationException(REQUIRED);
    }
    String normalized = PhpString.toLower(PhpString.truncate(trimmed, MAX_LENGTH));
    if (!PhpEmailValidator.isValid(normalized)) {
      throw new ValidationException(INVALID);
    }
    return new Email(normalized);
  }

  /**
   * Reprend un e-mail déjà normalisé (lu en base), sans contrôle.
   *
   * @param value e-mail stocké
   * @return e-mail
   */
  public static Email ofStored(String value) {
    return new Email(value);
  }
}
