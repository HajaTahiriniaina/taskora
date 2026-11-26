package com.taskora.identity.domain.model;

/** Adresse e-mail normalisée. */
public record Email(String value) {
  /** trim → obligatoire → tronqué à 180 → minuscules → format. */
  public static Email fromRegistration(String raw) {
    throw new UnsupportedOperationException("BackCodeur");
  }

  public static Email ofStored(String value) {
    throw new UnsupportedOperationException("BackCodeur");
  }
}
