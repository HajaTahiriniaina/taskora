package com.taskora.identity.domain.model;

import com.taskora.identity.domain.exception.ValidationException;

/** Nom affiché : trim → obligatoire → tronqué à 120 points de code. */
public record PersonName(String value) {
  public static final int MAX_LENGTH = 120;
  static final String REQUIRED = "Le champ \"name\" est obligatoire.";

  /**
   * Nom saisi : trim PHP, obligatoire, puis tronqué à 120 points de code.
   *
   * @param raw nom saisi ({@code null} = absent)
   * @return nom normalisé
   * @throws com.taskora.identity.domain.exception.ValidationException si vide après trim
   */
  public static PersonName of(String raw) {
    String trimmed = raw == null ? "" : PhpString.trim(raw);
    if (trimmed.isEmpty()) {
      throw new ValidationException(REQUIRED);
    }
    return new PersonName(PhpString.truncate(trimmed, MAX_LENGTH));
  }
}
