package com.taskora.identity.domain.model;

import com.taskora.identity.domain.exception.ValidationException;

/** Adresse e-mail normalisée. */
public record Email(String value) {
  public static final int MAX_LENGTH = 180;
  static final String REQUIRED = "Le champ \"email\" est obligatoire.";
  static final String INVALID = "Adresse e-mail invalide.";

  /** trim → obligatoire → tronqué à 180 → minuscules → format. */
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

  public static Email ofStored(String value) {
    return new Email(value);
  }
}
